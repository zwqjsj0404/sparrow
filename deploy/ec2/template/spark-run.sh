#!/bin/bash
# A Sparrow deployment version of spark's run script
SCALA_VERSION=2.9.1

FWDIR="spark"

# Export this as SPARK_HOME
export SPARK_HOME="$FWDIR"

# Load environment variables from conf/spark-env.sh, if it exists
if [ -e spark-env.sh ] ; then
  . spark-env.sh
fi

MESOS_HOME="/opt/mesos"
MESOS_CLASSPATH="$MESOS_HOME/lib/java/mesos.jar"
MESOS_LIBRARY_PATH="$MESOS_HOME/lib/java"

if [ "x$SPARK_MEM" == "x" ] ; then
  SPARK_MEM="1300m"
fi
export SPARK_MEM  # So that the process sees it and can report it to Mesos

# Set JAVA_OPTS to be able to load native libraries and to set heap size
JAVA_OPTS="$SPARK_JAVA_OPTS"
JAVA_OPTS+="-XX:+UseConcMarkSweepGC -Djava.library.path=$SPARK_LIBRARY_PATH:$FWDIR/lib:$FWDIR/src/main/native:$MESOS_LIBRARY_PATH"
JAVA_OPTS+=" -Xms$SPARK_MEM -Xmx$SPARK_MEM"
# Load extra JAVA_OPTS from conf/java-opts, if it exists
if [ -e $FWDIR/conf/java-opts ] ; then
  JAVA_OPTS+=" `cat $FWDIR/conf/java-opts`"
fi
export JAVA_OPTS

CORE_DIR=$FWDIR/core
REPL_DIR=$FWDIR/repl
EXAMPLES_DIR=$FWDIR/examples
TPC_DIR=$FWDIR/tpc
BAGEL_DIR=$FWDIR/bagel

# Build up classpath
CLASSPATH="$SPARK_CLASSPATH:$CORE_DIR/target/scala-$SCALA_VERSION/classes:$MESOS_CLASSPATH"
CLASSPATH+=:$FWDIR
CLASSPATH+=:$REPL_DIR/target/scala-$SCALA_VERSION/classes
CLASSPATH+=:$EXAMPLES_DIR/target/scala-$SCALA_VERSION/classes
CLASSPATH+=:$TPC_DIR/target/scala-$SCALA_VERSION/classes
for jar in `find $CORE_DIR/lib -name '*jar'`; do
  CLASSPATH+=:$jar
done
for jar in `find $FWDIR/lib_managed/jars -name '*jar'`; do
  CLASSPATH+=:$jar
done
for jar in `find $FWDIR/lib_managed/bundles -name '*jar'`; do
  CLASSPATH+=:$jar
done
for jar in `find $REPL_DIR/lib -name '*jar'`; do
  CLASSPATH+=:$jar
done
CLASSPATH+=:$BAGEL_DIR/target/scala-$SCALA_VERSION/classes
export CLASSPATH # Needed for spark-shell

if [ -n "$SCALA_HOME" ]; then
  SCALA=${SCALA_HOME}/bin/scala
else
  SCALA=scala
fi

exec $SCALA $SPARK_JAVA_OPTS -cp $CLASSPATH "$@"
