Clustered EJB Timers (persisted with postgresql) in jBPM
========================================================

**There was an issue at 7.52.0.Final (https://issues.redhat.com/browse/JBPM-9690) with KIE server multinode setups when the refresh-time of the timers was after task completition in a different node than the one that started the timer.**
**This project exercises different scenarios after patching the image with the solution provided by the PR (https://github.com/kiegroup/jbpm/pull/1908) to test that expected result is achieve and issue has been fixed**

## Covered scenarios

- User starts process in one node but complete task in another before refresh-time (timer is triggered after process is completed)
- User starts process in one node but complete task in another before refresh-time and session is still alive when timer is triggered (2nd human task waiting)
- User starts process in one node but complete task in another after refresh-time (regression scenario)

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

