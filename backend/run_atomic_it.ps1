$env:CLIPFETCH_MYSQL_IT_PASSWORD='localtest123'
mvn -f D:\release\clipfetch\backend\pom.xml -q '-Dtest=NativeMysqlAtomicLeaseIT' '-Dclipfetch.mysql.atomicLease=true' test
exit $LASTEXITCODE
