SELECT SUM(L_EXTENDEDPRICE * L_DISCOUNT) FROM LINEITEM WHERE 
    L_SHIPDATE >= DATE '1996-01-01' AND L_SHIPDATE < DATE '1997-01-01' AND 
    L_DISCOUNT BETWEEN 0.08 AND 0.1 AND L_QUANTITY < 24;
