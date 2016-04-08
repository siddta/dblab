SELECT PS_PARTKEY, SUM(PS_SUPPLYCOST*PS_AVAILQTY) AS VALUE
FROM NATION JOIN SUPPLIER ON N_NATIONKEY = S_NATIONKEY
JOIN PARTSUPP ON S_SUPPKEY = PS_SUPPKEY
WHERE N_NAME = 'UNITED KINGDOM'
GROUP BY PS_PARTKEY
HAVING VALUE > 
(SELECT SUM(PS_SUPPLYCOST * PS_AVAILQTY * 0.0001000000) AS TOTAL
FROM NATION JOIN SUPPLIER ON N_NATIONKEY = S_NATIONKEY
JOIN PARTSUPP ON S_SUPPKEY = PS_SUPPKEY
WHERE N_NAME = 'UNITED KINGDOM')
ORDER BY VALUE DESC
