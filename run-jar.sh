java -jar -XX:+UseG1GC -XX:MaxRAMPercentage=80 -XX:-UseCompressedOops -Xlog:gc*:file=logs/gc/gc_%t_%p.log:time,uptime,level,tags -verbose:gc.log target/scala-2.12/fpms-assembly-0.1.jar