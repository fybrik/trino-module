import trino

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
    cur = conn.cursor()
    return cur

if __name__ == "__main__":
    print("show catalogs")
    cur = connect_user("admin", "iceberg")
    res = execute_query(cur, "SHOW CATALOGS")
    print(res)

    print("create schema")
    schema_query = "create schema hive.icebergtrino  with (location = 's3a://iceberg/')"
    res = execute_query(cur, schema_query)
    print(res)


    print("create table")
    create_table_query = "create table iceberg.icebergtrino.logs (\
        level varchar not null,\
        event_time timestamp(6) with time zone NOT NULL,\
        message varchar NOT NULL,\
        call_stack array(varchar)\
        )\
        with (format = 'ORC', partitioning = ARRAY['hour(event_time)','level'])"
    
    res = execute_query(cur, create_table_query)
    print(res)

    print("insert rows to table")
    insert_query = "INSERT INTO iceberg.icebergtrino.logs VALUES\
                    (\
                        'ERROR',\
                        timestamp '2021-04-01' AT TIME ZONE 'America/Los_Angeles',\
                        'oh nooo',\
                        ARRAY ['Exception in thread main java.lang.NullPointerException']\
                    )"
    res = execute_query(cur, insert_query)
    print(res)

    print("create view")
    view_query = "create view iceberg.icebergtrino.viewtest as select event_time, message from iceberg.icebergtrino.logs" 
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
    select_query = 'select * from iceberg.icebergtrino.viewtest'
    res = execute_query(cur, select_query)
    print(res)

    