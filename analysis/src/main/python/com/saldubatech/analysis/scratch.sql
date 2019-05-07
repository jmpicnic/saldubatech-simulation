select * from information_schema.tables

select count(evid) from m_m_1_d;

select ev_type, count(evid) from m_m_1_d group by ev_type;

select * from (select load_id, min(ts) as min_ts, max(ts) as max_ts,  count(evid) as c from m_m_1_d group by load_id) as counts where c <> 12 order by min_ts asc;

select avg(e.ts - s.ts) as elapsed from
  (select ev_type, station_id, load_id, ts from m_m_1_b where station_id = 'MM1_Simulation_Source') as e
join (select ev_type, station_id, load_id, ts from m_m_1_b where station_id = 'MM1_Simulation_Source') as s
on e.load_id = s.load_id and e.station_id = s.station_id
where s.ev_type = 'Start' and e.ev_type = 'Complete';


select avg(e.ts - s.ts) as elapsed from
m_m_1_b as e join m_m_1_b as s on e.load_id = s.load_id
where e.ev_type = 'End' and s.ev_type = 'New';

select load_id, station_id||'_'||ev_type as stage, ts from m_m_1_d where station_id||'_'||ev_type = 'MM1_Simulation_Sink_Arrive' order by load_id, station_id||'_'||ev_type;

select load_id, ev_type as stage, ts from m_m_1_d where station_id = 'MM1_Simulation_Sink' and ev_type <> 'End' order by load_id, ev_type;

select distinct ev_type from m_m_1_d order by ev_type;

select * from crosstab('select load_id, ev_type, ts from m_m_1_b where station_id = ''MM1_Simulation_Sink'' and ev_type <> ''End'' order by 1, 2')
  as final_result(load varchar, arr bigint, dep bigint);

select load_id, Source_New, Source_Arrive, Source_Start, Source_Complete, Source_Depart, Server_Arrive, Server_Start, Server_Complete, Server_Depart, Sink_Arrive, Sink_Depart, Sink_End
from (select * from crosstab('select load_id, station_id||''_''||ev_type as stage, ts from m_m_1_b order by load_id, station_id||''_''||ev_type')
  as final_result(load_id varchar,
                  Sink_Arrive bigint,
                  Sink_Depart bigint,
                  Sink_End bigint,
                  Source_Arrive bigint,
                  Source_Complete bigint,
                  Source_Depart bigint,
                  Source_New bigint,
                  Source_Start bigint,
                  Server_Arrive bigint,
                  Server_Complete bigint,
                  Server_Depart bigint,
                  Server_Start bigint)) as ts_table order by source_new;


select load_id, source_arrive - source_new as arraival_delay,
       source_start - source_arrive as source_qt, source_complete - source_start as source_pt, source_depart - source_complete as source_depart_delay,
       server_arrive - source_depart as receive_transport_delay,
       server_start - server_arrive as server_qt, server_complete - server_start as server_pt, server_depart - server_complete as server_depart_delay,
       sink_arrive - server_depart as deliver_transport_delay,
       sink_depart - sink_arrive as sink_qt, sink_end - sink_depart as finishing_delay
from (select load_id, Source_New, Source_Arrive, Source_Start, Source_Complete, Source_Depart, Server_Arrive, Server_Start, Server_Complete, Server_Depart, Sink_Arrive, Sink_Depart, Sink_End
      from (select * from crosstab('select load_id, station_id||''_''||ev_type as stage, ts from m_m_1_b order by load_id, station_id||''_''||ev_type')
                            as final_result(load_id varchar,
                                            Sink_Arrive bigint,
                                            Sink_Depart bigint,
                                            Sink_End bigint,
                                            Source_Arrive bigint,
                                            Source_Complete bigint,
                                            Source_Depart bigint,
                                            Source_New bigint,
                                            Source_Start bigint,
                                            Server_Arrive bigint,
                                            Server_Complete bigint,
                                            Server_Depart bigint,
                                            Server_Start bigint)) as ts_table order by source_new) as lifecycle_times;


select avg(source_arrive - source_new)as arraival_delay,
       avg(source_start - source_arrive) as source_qt, avg(source_complete - source_start) as source_pt, avg(source_depart - source_complete) as source_depart_delay,
       avg(server_arrive - source_depart) as receive_transport_delay,
       avg(server_start - server_arrive) as server_qt, avg(server_complete - server_start) as server_pt, avg(server_depart - server_complete) as server_depart_delay,
       avg(sink_arrive - server_depart) as deliver_transport_delay,
       avg(sink_depart - sink_arrive) as sink_qt, avg(sink_end - sink_depart) as finishing_delay,
       avg(sink_end - source_new) as total_ct,
       avg(sink_end - source_depart) as real_ct
from (select load_id, Source_New, Source_Arrive, Source_Start, Source_Complete, Source_Depart, Server_Arrive, Server_Start, Server_Complete, Server_Depart, Sink_Arrive, Sink_Depart, Sink_End
      from (select * from crosstab('select load_id, station_id||''_''||ev_type as stage, ts from m_m_1_d order by load_id, station_id||''_''||ev_type')
                            as final_result(load_id varchar,
                                            Sink_Arrive bigint,
                                            Sink_Depart bigint,
                                            Sink_End bigint,
                                            Source_Arrive bigint,
                                            Source_Complete bigint,
                                            Source_Depart bigint,
                                            Source_New bigint,
                                            Source_Start bigint,
                                            Server_Arrive bigint,
                                            Server_Complete bigint,
                                            Server_Depart bigint,
                                            Server_Start bigint)) as ts_table order by source_new) as lifecycle_times;