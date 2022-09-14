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
Fybrik [Quick Start (v0.6)](https://fybrik.io/v0.6/get-started/quickstart/), without the section of `Install modules`.

### Register the fybrikmodule:
```bash
kubectl apply -f trino-module.yaml -n fybrik-system
```

### Create namespace
```bash
kubectl create namespace fybrik-notebook-sample
kubectl config set-context --current --namespace=fybrik-notebook-sample
```

### Register asset and secret
```bash
kubectl apply -f sample_assets/asset-iceberg.yaml -n fybrik-notebook-sample
```
In `sample_assets/asset-iceberg.yaml` tags can be added to the columns, here columns `a` and `d` is tagged with `PII` tag.

Replace the values for access_key and secret_key in `sample_asset/secret-iceberg.yaml` file with the values from the object storage service that you used and run:
```bash
kubectl apply -f sample_assets/secret-iceberg.yaml -n fybrik-notebook-sample
```

### Define data access policy
An example policy of remove columns with `PII` tag.
```bash
kubectl -n fybrik-system create configmap sample-policy --from-file=sample_assets/sample-policy.rego
kubectl -n fybrik-system label configmap sample-policy openpolicyagent.org/policy=rego
while [[ $(kubectl get cm sample-policy -n fybrik-system -o 'jsonpath={.metadata.annotations.openpolicyagent\.org/policy-status}') != '{"status":"ok"}' ]]; do echo "waiting for policy to be applied" && sleep 5; done
```

### Deploy Fybrik application which triggers the module
```bash
kubectl apply -f fybrikapplication.yaml
```
Run the following commands to wait until the fybrikapplication be ready.
```bash
while [[ $(kubectl get fybrikapplication my-notebook -o 'jsonpath={.status.ready}') != "true" ]]; do echo "waiting for FybrikApplication" && sleep 5; done

while [[ $(kubectl get fybrikapplication my-notebook -o 'jsonpath={.status.assetStates.fybrik-notebook-sample/iceberg-dataset.conditions[?(@.type == "Ready")].status}') != "True" ]]; do echo "waiting for fybrik-notebook-sample/iceberg-dataset asset" && sleep 5; done
```

Wait For the pod `my-notebook-default-trino-module-xxxx` to be completed. This pod runs a python code that registers the asset in trino and applies the policy to create a virtual dataset. The user can use the following username to connect to trino:

    "name": "user1"

For example, you can run trino docker container and run queries. First, check the docker container name of trino (the docker container with the image `trinodb/trino:latest`). Then, Run the following command to run trino server.
```bash
docker ps | grep trinodb/trino:latest
docker container exec -it <trino_container_name> trino --user user1
```
Check the tables that `user1` can see. It should be only the `view1`.
```bash
show tables from iceberg.icebergtrino;
```

You can run a query to select from the created view. It should return only allowed columns according to the policies.
```bash
select * from iceberg.icebergtrino.view1;
```
In the output we see only columns (b, c) but not (a, d) because they have a `PII` tag.

You can login into trino as `admin` user using the following command.
```bash
docker container exec -it <trino_container_name> trino --user admin
```
The admin user can see the original table which is `logs` table. 
```bash
show tables from iceberg.icebergtrino;
```
The command `show tables` should return the original table `logs` and the created view `view1`.

You can run a query to select from `logs` table. It should return all the columns.
```bash
select * from iceberg.icebergtrino.logs;
```
In the output we should see columns (a, b, c, d).

### Cleanup
When you're finished experimenting with a sample, you can clean up as follows.
- Deleting the view using `DROP` commands `drop view iceberg.icebergtrino.view1;`.
- Deleting the iceberg table must be done by `admin` user.
    ```bash
    docker container exec -it <trino_container_name> trino --user admin
    drop table iceberg.icebergtrino.logs;
    ```
- Clean the docker containers.
    ```bash
    cd trino-iceberg-minio/
    docker-compose down
    ```
