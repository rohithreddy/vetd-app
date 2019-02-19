DROP TABLE IF EXISTS vetd.enums;
--;;
CREATE TABLE vetd.enums (id bigint NOT NULL, idstr text, created timestamp with time zone, updated timestamp with time zone, deleted timestamp with time zone, fsubtype text, descr text)
--;;
ALTER TABLE vetd.enums OWNER TO vetd
--;;
GRANT SELECT ON TABLE vetd.enums TO hasura;