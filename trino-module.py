import time
import base64
import json
import trino
import yaml

data_dict = {}

def api_get(server, endpoint=None, headers=None, body=None):
    """" Run GET command """
    return json.loads(requests.get(url='{server}/api/v1/{endpoint}'.format(server=server, endpoint=endpoint), headers=headers, data=json.dumps(body)).text)


def api_post(server, endpoint=None, body=None, headers=None):
    """" Run POST command """
    text = requests.request("POST", '{server}/api/v1/{endpoint}'.format(server=server, endpoint=endpoint), headers=headers, data=json.dumps(body)).text

    # a post may return no data
    if (text):
        return json.loads(text)
    else:
        return None


def api_put(server, endpoint=None, body=None, headers=None):
    """" Run PUT command """
    return requests.put('{server}/api/v1/{endpoint}'.format(server=server, endpoint=endpoint), headers=headers, data=json.dumps(body)).text


def api_delete(server, endpoint=None, headers=None):
    """" Run DELETE command """
    return requests.delete('{server}/api/v1/{endpoint}'.format(server=server, endpoint=endpoint), headers=headers)

def execute_query(cur, query):
    cur.execute(query)
    # res = None
    try:
        res = cur.fetchall()
        return res
    except Exception as e:
        print(e)
    # return res

def connect_user(username, catalog):
    conn = trino.dbapi.connect(host='host.docker.internal', port=8080, user=username, catalog=catalog)
    # conn = trino.dbapi.connect(host='localhost', port=8080, user=username, catalog=catalog)
    cur = conn.cursor()
    return cur

def get_details_from_conf():
    """ Parse the configuration and get the data details and policies """
    with open("/etc/conf/conf.yaml", 'r') as stream:
    # with open("sample-conf.yaml", 'r') as stream:
        content = yaml.safe_load(stream)
        for key, val in content.items():
            if "data" in key:
                for data in val:
                    dataset_id = data["name"]
                    name = dataset_id.split("/")[1]
                    endpoint_url = data["connection"]["s3"]["endpoint_url"]
                    transformations = base64.b64decode(data["transformations"])
                    transformations_json = json.loads(transformations.decode('utf-8'))
                    transformation = transformations_json[0]['name']
                    transformation_cols = transformations_json[0][transformation]["columns"]
                    data_dict[name] = {'format': data["format"], 'endpoint_url': endpoint_url, 'path': data["path"], 'transformation': transformation,
                     'transformation_cols': transformation_cols}

    return data_dict[name]

def get_policy_query(transformation_cols, sql_path, col_names):
    request_cols = [col for col in col_names if col not in transformation_cols]
    if len(request_cols) < 1:
        return ""
    requested_cols_string = request_cols[0]
    for col in request_cols[1:]:
        add_col = ", " + col
        requested_cols_string += add_col
    sql_vds = "select " + requested_cols_string + " from " + sql_path
    return sql_vds

def update_rules(username, catalog, schema, table, privileges):
    jsonFile = open("/home/mohammadtn/trino/trino-server-386/etc/rules.json", "r")
    data = json.load(jsonFile) # Read the JSON into the buffer
    jsonFile.close()
    # data['tables'][1]['table'] = 'table'
    data['tables'].append({'user': username, 'catalog': catalog, 'schema': schema, 'table': table, 'privileges': privileges})

    jsonFile = open("/home/mohammadtn/trino/trino-server-386/etc/rules.json", "w+")
    jsonFile.write(json.dumps(data))
    jsonFile.close()


if __name__ == "__main__":
    print("show catalogs")
    cur = connect_user("admin", "iceberg")
    res = execute_query(cur, "SHOW CATALOGS")
    print(res)

    print("create schema")
    schema_query = "create schema hive.icebergtrino  with (location = 's3a://iceberg/')"
    res = execute_query(cur, schema_query)
    print(res)
    
    create_table_query = "create table iceberg.icebergtrino.logs (\
        a DOUBLE,\
        b DOUBLE,\
        c DOUBLE,\
        d DOUBLE\
        )\
        with (format = 'ORC')"

    res = execute_query(cur, create_table_query)
    print(res)

    print("insert rows to table")
    insert_query = "INSERT INTO iceberg.icebergtrino.logs VALUES\
                    (\
                        1,\
                        2,\
                        3,\
                        4\
                    )"

    res = execute_query(cur, insert_query)
    print(res)

    # Create a sql query for transformations from fybrik
    parse_conf = get_details_from_conf()
    transformation = parse_conf['transformation']
    transformation_cols = parse_conf['transformation_cols']

    # Get the columns of the new source
    sql_path = "iceberg.icebergtrino.logs"
    col_names = ["a", "b", "c", "d"]

    # Get the sql query from the policies
    sql_view = get_policy_query(transformation_cols, sql_path, col_names)


    print("create view")
    # view_query = "create view iceberg.icebergtrino.view1 as select event_time, message from iceberg.icebergtrino.logs"
    view_query = "create view iceberg.icebergtrino.view1 as " + sql_view
    print(view_query)
    res = execute_query(cur, view_query)
    print(res)

    print("select from the table")
    select_query = 'select * from iceberg.icebergtrino.logs'
    res = execute_query(cur, select_query)
    print(res)

    print("user1 select")
    cur = connect_user("user1", "iceberg")
    res = execute_query(cur, select_query)
    print(res)

    print("user1 select from the view")
    select_query = 'select * from iceberg.icebergtrino.view1'
    res = execute_query(cur, select_query)
    print(res)

    # Give permissions to user1
    # update_rules("user1", "iceberg", "icebergtrino", "view1", ["SELECT"])
    time.sleep(10)
    
    print("user1 select from the view")
    select_query = 'select * from iceberg.icebergtrino.view1'
    res = execute_query(cur, select_query)
    print(res)

    