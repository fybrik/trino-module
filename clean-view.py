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
    print("show catalogs clean")
    cur = connect_user("admin", "iceberg")
    res = execute_query(cur, "SHOW CATALOGS")
    print(res)

    drop_query = 'drop view if exists iceberg.icebergtrino.view1'
    res = execute_query(cur, drop_query)
    print(res)