

import psycopg2 as db
import pandas as pd

class data_source(object):
    def __init__(self, table_name, db_name = "salduba.events"):
        self._conn = db.connect(database=db_name, user="postgres", host="localhost", password="4database$")
        self._table_name = table_name

    def close(self):
        self._conn.close()

    def get_data(self, columns, where, group_order=None, select_override=None, join_override=None, indexes=None):
        if select_override is None:
            select = ", ".join(columns)
        else:
            select = select_override
        if join_override is None:
            table = self._table_name
        else:
            table = join_override
        query = "select "+select+" from "+table+" where "+where+" "+group_order
        if group_order is not None:
            query = query + group_order

        frame = self.get_data_from_query(query)
        return frame

    def get_data_from_query(self, query, columns=[], indexes=None):
        records= self.run_query(query)
        frame = pd.DataFrame.from_records(records, columns=columns, index=indexes)
        return frame

    def run_query(self, query):
        cur = self._conn.cursor()
        cur.execute(query)
        records = cur.fetchall()
        self._conn.commit()
        cur.close()
        return records
