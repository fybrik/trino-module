# trino-module

### Install trino
Install trino and minio using the following commands.
```bash
cd iceberg/trino-iceberg-minio/
docker-compose up -d
```
Then, create a bucket with name `iceberg` in minio using [these instructions](https://github.com/bitsondatadev/trino-getting-started/tree/main/hive/trino-minio#create-bucket-in-minio)

### Install fybrik
Fybrik Quick Start (v0.6), without the section of `Install modules`.

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
Replace the values for access_key and secret_key in `sample_asset/secret-iceberg.yaml` file with the values from the object storage service that you used and run:
```bash
kubectl apply -f sample_assets/secret-iceberg.yaml -n fybrik-notebook-sample
```

### Define data access policy
An example policy of remove columns.
```bash
kubectl -n fybrik-system create configmap sample-policy --from-file=sample_assets/sample-policy.rego
kubectl -n fybrik-system label configmap sample-policy openpolicyagent.org/policy=rego
while [[ $(kubectl get cm sample-policy -n fybrik-system -o 'jsonpath={.metadata.annotations.openpolicyagent\.org/policy-status}') != '{"status":"ok"}' ]]; do echo "waiting for policy to be applied" && sleep 5; done
```

### Deploy Fybrik application which triggers the module
```bash
kubectl apply -f fybrikapplication.yaml
```
Run the following command to wait until the fybrikapplication be ready.
```bash
while [[ $(kubectl get fybrikapplication my-notebook -o 'jsonpath={.status.ready}') != "true" ]]; do echo "waiting for FybrikApplication" && sleep 5; done
```

Wait For the pod `my-notebook-default-trino-module-xxxx` to be completed. This pod runs a python code that registers the asset in trino and applies the policy to create a virtual dataset. The user can use the following username to connect to trino:

    "name": "user1"

For example, you can run trino docker container and run queries. Run the following command to run trino server.
```bash
docker container exec -it trinoicebergminio_trino-coordinator_1 trino --user user1
```
Check the tables that `user1` can see. It should be only the `view1`.
```bash
show tables from iceberg.icebergtrino;
```

You can run a query to select from the created view. I should return only allowed columns according to the policies.
```bash
select * from iceberg.icebergtrino.view1;
```