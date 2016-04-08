SELECT S_NAME, COUNT(*) AS NUMWAIT
FROM NATION JOIN SUPPLIER ON N_NATIONKEY = S_NATIONKEY
JOIN LINEITEM L1 ON S_SUPPKEY = L_SUPPKEY
LEFT SEMI JOIN LINEITEM L2 ON L1.L_ORDERKEY = L_ORDERKEY AND L1.L_SUPPKEY != L_SUPPKEY
ANTI JOIN LINEITEM L3 ON L1.L_ORDERKEY = L_ORDERKEY AND L1.L_SUPPKEY != L_SUPPKEY
JOIN ORDERS ON L1.L_ORDERKEY = O_ORDERKEY
WHERE N_NAME = 'MOROCCO' AND L1.L_RECEIPTDATE > L1.L_COMMITDATE AND L3.L_RECEIPTDATE > L3.L_COMMITDATE AND O_ORDERSTATUS = 'F'
GROUP BY S_NAME
ORDER BY NUMWAIT DESC, S_NAME
