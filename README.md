# trino-module

### Before you begin
Ensure that you have the following:

- Helm 3.3 or greater must be installed and configured on your machine.
- Kubectl 1.18 or newer must be installed on your machine.
- Access to a Kubernetes cluster such as Kind as a cluster administrator.

### Install trino
Install trino and minio using the following commands.
```bash
cd trino-iceberg-minio/
docker-compose up -d
cd ..
```
Then, create a bucket with name `iceberg` in minio using [these instructions](https://github.com/bitsondatadev/trino-getting-started/tree/main/hive/trino-minio#create-bucket-in-minio)

### Install fybrik
Fybrik [Quick Start (v1.1)](https://fybrik.io/v1.1/get-started/quickstart/), without the section of `Install modules`.

### Register the fybrikmodule:
```bash
kubectl apply -f trino-module.yaml -n fybrik-system
```

### Create namespace
```bash
kubectl create namespace fybrik-notebook-sample
kubectl config set-context --current --namespace=fybrik-notebook-sample
```

### Register asset
Replace the values of `endpoint`, `bucket`, and `object_key` in `sample_asset/asset-iceberg`.yaml file according to your created asset. Then, add the asset to the internal catalog using the following command:
```bash
kubectl apply -f sample_assets/asset-iceberg.yaml -n fybrik-notebook-sample
```
The asset has been marked as a `finance` data and the columns `a` and `d` have been marked with `PII` tag.

### Register secret
Replace the values for `access_key` and `secret_key` in `sample_asset/secret-iceberg.yaml` file with the values from the object storage service that you used and run:
```bash
kubectl apply -f sample_assets/secret-iceberg.yaml -n fybrik-notebook-sample
```

### Define data access policy
Register a policy. The example policy removes columns tagged as `PII` from datasets marked as `finance`.
```bash
kubectl -n fybrik-system create configmap sample-policy --from-file=sample_assets/sample-policy.rego
kubectl -n fybrik-system label configmap sample-policy openpolicyagent.org/policy=rego
while [[ $(kubectl get cm sample-policy -n fybrik-system -o 'jsonpath={.metadata.annotations.openpolicyagent\.org/policy-status}') != '{"status":"ok"}' ]]; do echo "waiting for policy to be applied" && sleep 5; done
```

### Deploy Fybrik application which triggers the module
```bash
kubectl apply -f fybrikapplication.yaml
```
Run the following command to wait Wait for the fybrik module:
```bash
while [[ ($(kubectl get fybrikapplication my-notebook -o 'jsonpath={.status.ready}') != "true") || ($(kubectl get jobs my-notebook-fybrik-notebook-sample-trino-module -n fybrik-blueprints -o 'jsonpath={.status.conditions[0].type}') != "Complete") ]]; do echo "waiting for FybrikApplication" && sleep 5; done
```

The module runs a python code that registers the asset in trino and applies the policy to create a virtual dataset. The user can use the following username to connect to trino:

    "name": "user1"

For example, you can run trino docker container and run queries. First, check the docker container name of trino (the docker container with the image `trinodb/trino:latest`).
```bash
docker ps | grep trinodb/trino:latest
```
Then, Run the following command to run trino server with `admin` user:
```bash
docker container exec -it <trino_container_name> trino --user admin
```
The admin user can see the original table which is `logs` table:
```bash
show tables from iceberg.icebergtrino;
```
The command `show tables` should return the original table `logs` and the created view `view1`.

You can run a query to select from `logs` table. It should return all the columns.
```bash
select * from iceberg.icebergtrino.logs;
```
In the output we should see columns (a, b, c, d).

You can login into trino as `user1` user using the following command (after exiting from trino container):
```bash
docker container exec -it <trino_container_name> trino --user user1
```

You can run a query as `user1` to select from `logs` table. It should return only allowed columns according to the policies:
```bash
select * from iceberg.icebergtrino.logs;
```
In the output we see only columns (b, c) but not (a, d) because they have a `PII` tag.
This query goes through a proxy trino that modifies the query to select from `view1` view instead of `logs` table because `user1` is allowed to select from `view1` only and not from `logs`.


### Cleanup
When you're finished experimenting with a sample, you can clean up as follows.
1. Deleting the view using `DROP` commands `drop view iceberg.icebergtrino.view1;`.
1. Deleting the iceberg table must be done by `admin` user:
    ```bash
    docker container exec -it <trino_container_name> trino --user admin
    drop table iceberg.icebergtrino.logs;
    ```
1. Clean the docker containers:
    ```bash
    cd trino-iceberg-minio/
    docker-compose down
    cd ..
    ```
1. Delete the `fybrik-notebook-sample` namespace:
    ```bash
    kubectl delete namespace fybrik-notebook-sample
    ```
1. Delete the policy created in the `fybrik-system` namespace:
    ```bash
    NS="fybrik-system"; kubectl -n $NS get configmap | awk '/sample/{print $1}' | xargs  kubectl delete -n $NS configmap
    ```
