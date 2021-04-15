Clustered EJB Timers (persisted with postgresql) in jBPM
========================================================

There was an issue at **7.52.0.Final** [JBPM-9690](https://issues.redhat.com/browse/JBPM-9690) with KIE server multinode setups when task completition in a different node than the one that started the timer and process has already finished (session not found).

This project exercises different scenarios after patching the image with the solution provided by the PR [jbpm#1908](https://github.com/kiegroup/jbpm/pull/1908) to test that expected result is achieved and issue has been fixed.

It takes advantage of [testcontainers](https://www.testcontainers.org) library, creating images on-the-fly, from the multistage Dockerfile that it's used to:
- build the kjar project
- patch the image with the fixed classes at the corresponding jar
- invoke a custom cli scripts to configure postgresql persistence and the clustered EJB timers support

## Covered scenarios

- User starts process in one node but complete task in another before refresh-time (timer is triggered after process is completed)
- User starts process in one node but complete task in another after refresh-time *(regression scenario)*
- User starts process in one node but complete task in another before refresh-time and session is still alive when timer is triggered (2nd human task waiting)
- User starts process in one node but complete task in another after refresh-time and session is still alive when timer is triggered (2nd human task waiting) *(regression scenario)*

## Process reproducer

For the first and second scenarios, reproducer process contains just one human task, so process finishes after completing the human task

![Screenshot from 2021-04-15 10-01-33](https://user-images.githubusercontent.com/1962786/114835204-9d6faf00-9dd1-11eb-8401-648da02f703d.png)

- *refresh-interval* is 10 seconds
- *not-completed notification* repeated each 15 seconds
- process started in node 1, but task completed at node 2 (therefore notification is launched at node 1)



For the third and fourth scenarios, a second human task is waiting, so session is still alive when the notification triggers.

![Screenshot from 2021-04-15 10-00-46](https://user-images.githubusercontent.com/1962786/114835095-829d3a80-9dd1-11eb-8039-23ad91343f72.png)


## Test setup
![Screenshot from 2021-04-15 18-09-20](https://user-images.githubusercontent.com/1962786/114902162-d9c4fe80-9e15-11eb-9664-b0a09a6bdb67.png)



## Building

For building this project locally, you firstly need to have the following tools installed locally:
- git client
- Java 1.8
- Maven
- docker (because of testcontainers makes use of it).

Once you cloned the repository locally all you need to do is execute the following Maven build:

```
mvn clean install
```

for the `kie-server-showcase` scenarios (-Pkie-server, activated by default).

