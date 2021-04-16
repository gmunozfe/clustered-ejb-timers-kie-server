Clustered EJB Timers (persisted with postgresql) in jBPM
========================================================

There was an issue at **jBPM 7.52.0.Final** tracked by [JBPM-9690](https://issues.redhat.com/browse/JBPM-9690) with KIE server multinode setups: if a task is completed in a different node than the one that started the timer and process has already finished (no session found in that context), an incorrect reschedule loop is happening.

This project exercises different scenarios after patching the image with the solution provided by the PR [jbpm#1908](https://github.com/kiegroup/jbpm/pull/1908) to test that expected result is achieved and issue has been fixed.

It takes advantage of [testcontainers](https://www.testcontainers.org) library, creating images on-the-fly, from the multistage Dockerfile that it's used to:
- build the kjar project
- patch the image with the fixed classes at the corresponding jar
- invoke a custom cli scripts to configure postgresql persistence and the clustered EJB timers support

## Covered scenarios

For all covered scenarios, user starts process in one node (node 1) but completes task in another (node 2). 
However, there are different combinations based on:
- there is EJB timer cluster or not
- task is completed before or after refresh-time
- process has finished (session not found) or still alive (waiting on a second human task) when notification is triggered

Following decision table summarizes which scenarios were failing or not (regression testing) before applying the patch.

<table class="table">
  <thead class="thead-dark">
    <tr>
      <th>
      <th align="center" colspan="2">Cluster</th>
      <th align="center" colspan="2">No Cluster</th>
    </tr>
    <tr>
      <th>
      <th>Finished
      <th>Not Finished
      <th>Finished
      <th>Not Finished
  </thead>
  <tr>
    <td align="center">task completed <strong>before</strong> refresh
    <td align="center">regression
    <td align="center"><em>failing</em>
    <td align="center">regression
    <td align="center"><em>failing</em>
   <tr>
    <td align="center">task completed <strong>after</strong> refresh
    <td align="center">regression
    <td align="center">regression
    <td align="center">regression
    <td align="center"><em>failing</em>
</table>

## Reproducer processes

For the *finished* scenarios, reproducer process contains just one human task, so process finishes after completing the human task.

![Screenshot from 2021-04-15 10-01-33](https://user-images.githubusercontent.com/1962786/114835204-9d6faf00-9dd1-11eb-8401-648da02f703d.png)

- *refresh-interval* is 10 seconds
- *not-completed notification* repeated each 15 seconds
- process started in node 1, and task completed at node 2


For the *not-finished* scenarios, reproducer process contains a second human task that keeps on waiting, so session is still alive when the notification triggers.

![Screenshot from 2021-04-15 10-00-46](https://user-images.githubusercontent.com/1962786/114835095-829d3a80-9dd1-11eb-8039-23ad91343f72.png)


## Test setup
![Screenshot from 2021-04-15 18-35-27](https://user-images.githubusercontent.com/1962786/114905634-74730c80-9e19-11eb-998d-1f0488110870.png)

Test class instantiates different containers:
- *postgresql* module with initialization script under `/docker-entrypoint-initdb.d` containing `postgresql-jbpm-schema.sql` as explained [here](https://hub.docker.com/_/postgres)
- two generic containers with the `jboss/kie-server-showcase:7.52.0.Final` image modified by Dockerfile with the [postgresql datasource configuration](https://access.redhat.com/documentation/en-us/red_hat_jboss_enterprise_application_platform/7.3/html-single/configuration_guide/index#example_postgresql_datasource) and the `timer-service`configuration for EJB timers persistence. Patched jar with the fix will also override the targeted jar.

:construction: Multistage Dockerfile is also in charge of building the business application (kjar) used in the scenarios after pulling the *maven* image.

A shared network allows to communicate among containers with the *mappedPort*: KIE servers and postgresql will listen on a random free port, avoiding port clashes and skipping port offsets redefinition. 

:information_source: Notice that for no clustering EJB timers is needed to define *partition* with a different name for each node. Therefore, CLI script to configure datasource and EJB timer cluster would be common for both, parameterizing only `partition_name` for each node.

:bulb: By attaching an output log consumer with different prefix at KIE container startup, traces for each node will be easily distinguished:
```
[KIE-LOG-node2] STDOUT: 20:32:08,728 INFO  [io.undertow.accesslog] (default task-1) 172.21.0.1 [15/Apr/2021:20:32:08 +0000] "PUT /kie-server/services/rest/server/containers/org.kie.server.testing:cluster-ejb-sample:1.0.0/tasks/1/states/completed HTTP/1.1" 201 
[KIE-LOG-node1] STDOUT: 20:32:22,410 DEBUG [org.jbpm.services.ejb.timer.EJBTimerScheduler] (EJB default - 1) About to execute timer for job EjbTimerJob [timerJobInstance=GlobalJpaTimerJobInstance [timerServiceId=org.kie.server.testing:cluster-ejb-sample:1.0.0-timerServiceId, getJobHandle()=EjbGlobalJobHandle [uuid=1_1_END]]]

```

## Building

For building this project locally, you firstly need to have the following tools installed locally:
- git client
- Java 1.8
- Maven
- docker (because of testcontainers makes use of it).

Once you cloned the repository locally all you need to do is execute the following Maven build (for cluster scenarios):

```
mvn clean install
```

and the following for no-cluster scenarios:

```
mvn clean install -Dorg.kie.samples.ejbtimer.nocluster=true
```

This project is using only `kie-server-showcase` image but it is prepared for adding other images defining other profiles (-Pkie-server, activated by default).

Happy confirmation testing!! :tada::tada::tada:
