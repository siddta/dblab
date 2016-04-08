SELECT N_NAME, SUM(L_EXTENDEDPRICE*(1-L_DISCOUNT)) AS REVENUE
FROM REGION JOIN NATION ON R_REGIONKEY = N_REGIONKEY
    JOIN CUSTOMER ON N_NATIONKEY = C_NATIONKEY
    JOIN ORDERS ON C_CUSTKEY = O_CUSTKEY
    JOIN LINEITEM ON O_ORDERKEY = L_ORDERKEY
    JOIN SUPPLIER ON L_SUPPKEY = S_SUPPKEY AND N_NATIONKEY = S_NATIONKEY
WHERE R_NAME = 'ASIA' AND O_ORDERDATE >= DATE '1996-01-01' 
AND O_ORDERDATE < DATE '1997-01-01'
GROUP BY N_NAME
ORDER BY REVENUE DESC
