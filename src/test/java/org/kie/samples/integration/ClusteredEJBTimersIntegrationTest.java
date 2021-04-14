package org.kie.samples.integration;

import static java.util.Collections.singletonMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.jupiter.api.DisplayName;
import org.kie.api.task.model.Status;
import org.kie.samples.integration.testcontainers.KieServerContainer;
import org.kie.server.api.marshalling.MarshallingFormat;
import org.kie.server.api.model.KieContainerResource;
import org.kie.server.api.model.ReleaseId;
import org.kie.server.api.model.instance.ProcessInstance;
import org.kie.server.api.model.instance.TaskSummary;
import org.kie.server.client.KieServicesClient;
import org.kie.server.client.KieServicesConfiguration;
import org.kie.server.client.KieServicesFactory;
import org.kie.server.client.ProcessServicesClient;
import org.kie.server.client.QueryServicesClient;
import org.kie.server.client.UserTaskServicesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.dockerjava.api.DockerClient;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Testcontainers(disabledWithoutDocker=true)
public class ClusteredEJBTimersIntegrationTest {
    
    private static final String PREFIX_CLI_PATH = "src/test/resources/etc/jbpm-custom-";
	private static final String SELECT_COUNT_FROM_JBOSS_EJB_TIMER = "select count(*) from jboss_ejb_timer";
    public static final String ARTIFACT_ID = "cluster-ejb-sample";
    public static final String GROUP_ID = "org.kie.server.testing";
    public static final String VERSION = "1.0.0";
    public static final String ALIAS = "-alias";
    
    public static final String DEFAULT_USER = "kieserver";
    public static final String DEFAULT_PASSWORD = "kieserver1!";

    public static String containerId = GROUP_ID+":"+ARTIFACT_ID+":"+VERSION;

    private static Logger logger = LoggerFactory.getLogger(ClusteredEJBTimersIntegrationTest.class);
    
    private static Map<String, String> args = new HashMap<>();

    static {
        args.put("IMAGE_NAME", System.getProperty("org.kie.samples.image"));
        args.put("START_SCRIPT", System.getProperty("org.kie.samples.script"));
        args.put("SERVER", System.getProperty("org.kie.samples.server"));
        createCLIFile("node1");
        createCLIFile("node2");
    }
    
    
    
    @ClassRule
    public static Network network = Network.newNetwork();
    
    @ClassRule
    public static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:11.1")
                                        .withDatabaseName("rhpamdatabase")
                                        .withUsername("rhpamuser")
                                        .withPassword("rhpampassword")
                                        .withFileSystemBind("etc/postgresql", "/docker-entrypoint-initdb.d",
                                                            BindMode.READ_ONLY)
                                        .withNetwork(network)
                                        .withNetworkAliases("postgresql11");

    @ClassRule
    public static KieServerContainer kieServer1 = new KieServerContainer("node1", network, args);
    
    @ClassRule
    public static KieServerContainer kieServer2 = new KieServerContainer("node2", network, args);
    
    private static KieServicesClient ksClient1;
    private static KieServicesClient ksClient2;
    
    @BeforeClass
    public static void setup() {
        logger.info("KIE SERVER 1 started at port "+kieServer1.getKiePort());
        logger.info("KIE SERVER 2 started at port "+kieServer2.getKiePort());
        logger.info("postgresql started at "+postgreSQLContainer.getJdbcUrl());
        
        ksClient1 = authenticate(kieServer1.getKiePort(), DEFAULT_USER, DEFAULT_PASSWORD);
        ksClient2 = authenticate(kieServer2.getKiePort(), DEFAULT_USER, DEFAULT_PASSWORD);
        
        createContainer(ksClient1);
        createContainer(ksClient2);
    }
    
    private static void createCLIFile(String nodeName) {
        try {
             String content = FileUtils.readFileToString(new File(PREFIX_CLI_PATH+"template.cli"), "UTF-8");
             content = content.replaceAll("%partition_name%", "\\\"ejb_timer_"+nodeName+"_part\\\"");
             File cliFile = new File(PREFIX_CLI_PATH+nodeName+".cli");
             FileUtils.writeStringToFile(cliFile, content, "UTF-8");
             cliFile.deleteOnExit();
          } catch (IOException e) {
             throw new RuntimeException("Generating file failed", e);
          }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        ksClient1.disposeContainer(containerId);
        ksClient2.disposeContainer(containerId);
        DockerClient docker = DockerClientFactory.instance().client();
        docker.listImagesCmd().withLabelFilter("autodelete=true").exec().stream()
         .filter(c -> c.getId() != null)
         .forEach(c -> docker.removeImageCmd(c.getId()).withForce(true).exec());
    }

	@Test
    @DisplayName("user starts process in one node but complete task in another before refresh-time")
    public void completeTaskBeforeRefresh() throws Exception {
        
        ProcessServicesClient processClient = ksClient1.getServicesClient(ProcessServicesClient.class);
        Long processInstanceId = processClient.startProcess(containerId, /*"process2HumanTasks"*/ "taskWithEscalation", singletonMap("id", "id1"));
        assertNotNull(processInstanceId);
        
        UserTaskServicesClient taskClient = ksClient2.getServicesClient(UserTaskServicesClient.class);
        List<String> status = Arrays.asList(Status.Ready.toString());
        List<TaskSummary> taskList = taskClient.findTasksByStatusByProcessInstanceId(processInstanceId, status, 0, 10);

        TaskSummary taskSummary = taskList.get(0);
        logger.info("Starting task {} on kieserver2", taskSummary.getId());
        taskClient.startTask(containerId, taskSummary.getId(), DEFAULT_USER);
        
        assertEquals("there should be just one timer at the table",
                      1, performQuery(SELECT_COUNT_FROM_JBOSS_EJB_TIMER).getInt(1));
        
        assertEquals("timer should be started at node1 partition",
                     "ejb_timer_node1_part", performQuery("select partition_name from jboss_ejb_timer").getString(1));
        
        taskClient.completeTask(containerId, taskSummary.getId(), DEFAULT_USER, null);
        logger.info("Completed task {} on kieserver2", taskSummary.getId());
        
        Thread.sleep(15000);
        
        assertEquals("there shouldn't be any timer at the table",
                     0, performQuery(SELECT_COUNT_FROM_JBOSS_EJB_TIMER).getInt(1));
    }
    
    @Test
    @DisplayName("user starts process in one node but complete task in another before refresh-time and session is still alive (2nd human task waiting)")
    public void completeTaskBeforeRefreshWithAliveSession() throws Exception {
        
        ProcessServicesClient processClient = ksClient1.getServicesClient(ProcessServicesClient.class);
        Long processInstanceId = processClient.startProcess(containerId, "process2HumanTasks", singletonMap("id", "id1"));
        assertNotNull(processInstanceId);
        
        UserTaskServicesClient taskClient = ksClient2.getServicesClient(UserTaskServicesClient.class);
        List<String> status = Arrays.asList(Status.Ready.toString());
        List<TaskSummary> taskList = taskClient.findTasksByStatusByProcessInstanceId(processInstanceId, status, 0, 10);

        TaskSummary taskSummary = taskList.get(0);
        logger.info("Starting task {} on kieserver2", taskSummary.getId());
        taskClient.startTask(containerId, taskSummary.getId(), DEFAULT_USER);
        
        assertEquals("there should be just one timer at the table",
                      1, performQuery(SELECT_COUNT_FROM_JBOSS_EJB_TIMER).getInt(1));
        
        assertEquals("timer should be started at node1 partition",
                     "ejb_timer_node1_part", performQuery("select partition_name from jboss_ejb_timer").getString(1));
        
        taskClient.completeTask(containerId, taskSummary.getId(), DEFAULT_USER, null);
        logger.info("Completed task {} on kieserver2", taskSummary.getId());
        
        Thread.sleep(15000);
        
        assertEquals("there shouldn't be any timer at the table",
                     0, performQuery(SELECT_COUNT_FROM_JBOSS_EJB_TIMER).getInt(1));
        
        taskList = taskClient.findTasksByStatusByProcessInstanceId(processInstanceId, status, 0, 10);

        assertEquals(1, taskList.size());
        
        abortProcess(ksClient2, processClient, processInstanceId);
    }
    
    @Test
    @DisplayName("user starts process in one node but complete task in another after refresh-time")
    public void completeTaskAfterRefresh() throws Exception {
        
        ProcessServicesClient processClient = ksClient1.getServicesClient(ProcessServicesClient.class);
        Long processInstanceId = processClient.startProcess(containerId, "taskWithEscalation", singletonMap("id", "id1"));
        assertNotNull(processInstanceId);
        
        logger.info("Sleeping 11s so the refresh time is call off");
        Thread.sleep(11000);
        
        UserTaskServicesClient taskClient = ksClient2.getServicesClient(UserTaskServicesClient.class);
        List<String> status = Arrays.asList(Status.Ready.toString());
        List<TaskSummary> taskList = taskClient.findTasksByStatusByProcessInstanceId(processInstanceId, status, 0, 10);

        TaskSummary taskSummary = taskList.get(0);
        logger.info("Starting task {} on kieserver2", taskSummary.getId());
        taskClient.startTask(containerId, taskSummary.getId(), DEFAULT_USER);
        
        assertEquals("there should be just one timer at the table",
                      1, performQuery(SELECT_COUNT_FROM_JBOSS_EJB_TIMER).getInt(1));
        
        assertEquals("timer should be started at node1 partition",
                     "ejb_timer_node1_part", performQuery("select partition_name from jboss_ejb_timer").getString(1));
        
        taskClient.completeTask(containerId, taskSummary.getId(), DEFAULT_USER, null);
        logger.info("Completed task {} on kieserver2", taskSummary.getId());
        
        Thread.sleep(4000);
        
        assertEquals("there shouldn't be any timer at the table",
                     0, performQuery(SELECT_COUNT_FROM_JBOSS_EJB_TIMER).getInt(1));
    }
    
    private static void createContainer(KieServicesClient client) {
        ReleaseId releaseId = new ReleaseId(GROUP_ID, ARTIFACT_ID, VERSION);
        KieContainerResource resource = new KieContainerResource(containerId, releaseId);
        resource.setContainerAlias(ARTIFACT_ID + ALIAS);
        client.createContainer(containerId, resource);
    }

    private static KieServicesClient authenticate(int port, String user, String password) {
        String serverUrl = "http://localhost:" + port + "/kie-server/services/rest/server";
        KieServicesConfiguration configuration = KieServicesFactory.newRestConfiguration(serverUrl, user, password);
        
        configuration.setTimeout(60000);
        configuration.setMarshallingFormat(MarshallingFormat.JSON);
        return  KieServicesFactory.newKieServicesClient(configuration);
    }
    
    private void abortProcess(KieServicesClient kieServicesClient, ProcessServicesClient processClient, Long processInstanceId) {
        QueryServicesClient queryClient = kieServicesClient.getServicesClient(QueryServicesClient.class);
        
        ProcessInstance processInstance = queryClient.findProcessInstanceById(processInstanceId);
        assertNotNull(processInstance);
        assertEquals(1, processInstance.getState().intValue());
        processClient.abortProcessInstance(containerId, processInstanceId);
    }
    
    private DataSource getDataSource() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        hikariConfig.setUsername(postgreSQLContainer.getUsername());
        hikariConfig.setPassword(postgreSQLContainer.getPassword());
        hikariConfig.setDriverClassName(postgreSQLContainer.getDriverClassName());

        return new HikariDataSource(hikariConfig);
    }
    
    protected ResultSet performQuery(String sql) throws SQLException {
        DataSource ds = getDataSource();
        Statement statement = ds.getConnection().createStatement();
        statement.execute(sql);
        ResultSet resultSet = statement.getResultSet();

        resultSet.next();
        return resultSet;
    }
}

