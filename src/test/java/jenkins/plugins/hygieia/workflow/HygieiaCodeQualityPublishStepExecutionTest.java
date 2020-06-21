package jenkins.plugins.hygieia.workflow;

import com.capitalone.dashboard.model.CodeQualityType;
import com.capitalone.dashboard.model.quality.*;
import com.capitalone.dashboard.request.BuildDataCreateRequest;
import com.capitalone.dashboard.request.CodeQualityCreateRequest;
import hudson.FilePath;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import jenkins.plugins.hygieia.HygieiaPublisher;
import jenkins.plugins.hygieia.HygieiaResponse;
import jenkins.plugins.hygieia.HygieiaService;
import org.apache.http.HttpStatus;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import hudson.remoting.Callable;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import java.io.File;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@RunWith(PowerMockRunner.class)
@PrepareForTest({Job.class, Run.class})
public class HygieiaCodeQualityPublishStepExecutionTest {

    @Mock
    private HygieiaCodeQualityPublishStep mockStep;

    @Mock
    private TaskListener listener;

    @Mock
    private Run mockRun;

    @Mock
    private VirtualChannel mockChannel;

    private JAXBContext context;

    @Mock
    private HygieiaService mockHygieiaService;

    @Mock
    private PrintStream mockPrintStream;

    @InjectMocks
    private HygieiaCodeQualityPublishStep.HygieiaCodeQualityPublisherStepExecution subject;

    @Before
    public void setup() throws JAXBException {
        context = JAXBContext.newInstance(JunitXmlReport.class, JacocoXmlReport.class,
                FindBugsXmlReport.class, CheckstyleReport.class, PmdReport.class);
        subject.filepath = new FilePath(mockChannel,"remote");


        when(mockStep.getContext()).thenReturn(context);
        when(listener.getLogger()).thenReturn(mockPrintStream);
    }

    private void expectationsForBuildJob(int codeQualityStatus){
        HygieiaPublisher.DescriptorImpl mockDesc = mock(HygieiaPublisher.DescriptorImpl.class);
        when(mockStep.getHygieiaDesc()).thenReturn(mockDesc);
        when(mockDesc.getHygieiaJenkinsName()).thenReturn("jenkins");

        Job mockJob = PowerMockito.mock(Job.class);
        when(mockRun.getParent()).thenReturn(mockJob);
        when(mockJob.getAbsoluteUrl()).thenReturn("http://jenkins.url.com/parent/job/jenkinsJob/");

        when(mockJob.getName()).thenReturn("jenkinsJob");
        doReturn("jenkinsFullName").when(mockJob).getFullName();

        ItemGroup mockJobParent = mock(ItemGroup.class);
        doReturn(mockJobParent).when(mockJob).getParent();

        when(mockStep.getService()).thenReturn(mockHygieiaService);

        HygieiaResponse mockBuildResponse = mock(HygieiaResponse.class);
        when(mockHygieiaService.publishBuildData(any(BuildDataCreateRequest.class))).thenReturn(mockBuildResponse);

        when(mockBuildResponse.getResponseValue()).thenReturn("buildCollectorId");
        HygieiaResponse mockCodeResponse = mock(HygieiaResponse.class);
        when(mockCodeResponse.getResponseCode()).thenReturn(codeQualityStatus);
        when(mockCodeResponse.toString()).thenReturn("response2.toString");
        when(mockHygieiaService.publishSonarResults(any(CodeQualityCreateRequest.class))).thenReturn(mockCodeResponse);

    }

    @Test
    public void linksBuildJobAndCodeAnalysisTogether() throws Throwable {
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        CodeQualityCreateRequest capturedRequest = captor.getValue();
        assertThat(capturedRequest.getHygieiaId()).isEqualTo("buildCollectorId");
        assertThat(capturedRequest.getProjectId()).isEqualTo("jenkinsFullName");
        assertThat(capturedRequest.getProjectName()).isEqualTo("jenkinsFullName");
        assertThat(capturedRequest.getNiceName()).isEqualTo("jenkins");
        assertThat(capturedRequest.getType()).isEqualTo(CodeQualityType.StaticAnalysis);

        verify(mockPrintStream).println("Hygieia: Published Complete Metric Data. response2.toString");

    }

    @Test
    public void failureToPublishCodeDataIsLogged() throws Exception {
        this.expectationsForBuildJob(HttpStatus.SC_BAD_REQUEST);


        subject.run();

        verify(mockPrintStream).println("Hygieia: Failed Publishing Complete Metric Data. response2.toString");

    }

    @Test
    public void publishesBuildData() throws Throwable {
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);


        subject.run();

        ArgumentCaptor<BuildDataCreateRequest> captor = ArgumentCaptor.forClass(BuildDataCreateRequest.class);
        verify(mockHygieiaService).publishBuildData(captor.capture());

        BuildDataCreateRequest capturedRequest = captor.getValue();
        assertThat(capturedRequest.getJobUrl()).isEqualTo("http://jenkins.url.com/parent/job/jenkinsJob/");

        verify(mockPrintStream).println("Produced 0 metrics, publishing to Hygieia");

    }

    @Test
    public void runCollectsJunitResultFromJob() throws Throwable {
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);
        when(mockStep.getJunitFilePattern()).thenReturn("**/target/junit.xml");
        FilePath[] files = new FilePath[]{new FilePath(new File(this.getClass().getResource("/junit.xml").toURI()))};

        when(mockChannel.call(any(Callable.class))).thenReturn(files);

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(4);
        verify(mockPrintStream).println("Analysing 1 junit file(s)");
    }

    @Test
    public void copesWithJunitNotDefined() throws Exception {
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        when(mockStep.getJunitFilePattern()).thenReturn(null);

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(0);
        verify(mockPrintStream).println("Skipping junit analysis");

    }

    @Test
    public void copesWithJunitEmpty() throws Exception {
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        when(mockStep.getJunitFilePattern()).thenReturn("");

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(0);
        verify(mockPrintStream).println("Skipping junit analysis");

    }

    @Test
    public void ignoresFileIncorrectlyIdentified() throws Throwable {
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        when(mockStep.getJunitFilePattern()).thenReturn("**/target/junit.xml");
        // javadoc says it may be empty but not null
        FilePath[] files = new FilePath[0];

        when(mockChannel.call(any(Callable.class))).thenReturn(files);

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(0);
        verify(mockPrintStream).println("Analysing 0 junit file(s)");

    }

    @Test
    public void copesWithEmptyFile() throws Throwable {
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        when(mockStep.getJunitFilePattern()).thenReturn("**/target/junit.xml");
        FilePath[] files = new FilePath[]{};

        when(mockChannel.call(any(Callable.class))).thenReturn(files);

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(0);
        verify(mockPrintStream).println("Analysing 0 junit file(s)");
    }

    @Test
    public void doesPmd() throws Throwable {
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        when(mockStep.getPmdFilePattern()).thenReturn("**/target/pmd.xml");
        FilePath[] files = new FilePath[]{new FilePath(new File(this.getClass().getResource("/pmd.xml").toURI()))};

        when(mockChannel.call(any(Callable.class))).thenReturn(files);

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(4);
        verify(mockPrintStream).println("Analysing 1 pmd file(s)");
    }

    @Test
    public void pmdNull() throws Exception{
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        when(mockStep.getPmdFilePattern()).thenReturn(null);

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(0);
        verify(mockPrintStream).println("Skipping pmd analysis");
    }

    @Test
    public void pmdEmpty() throws Exception{
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        when(mockStep.getPmdFilePattern()).thenReturn("");

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(0);
        verify(mockPrintStream).println("Skipping pmd analysis");
    }

    @Test
    public void doesFindbugs() throws Throwable {
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        when(mockStep.getFindbugsFilePattern()).thenReturn("**/target/findbugs.xml");
        FilePath[] files = new FilePath[]{new FilePath(new File(this.getClass().getResource("/findbugs.xml").toURI()))};

        when(mockChannel.call(any(Callable.class))).thenReturn(files);

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(4);
        verify(mockPrintStream).println("Analysing 1 findbugs file(s)");
    }

    @Test
    public void findbugsNull() throws Exception{
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        when(mockStep.getFindbugsFilePattern()).thenReturn(null);

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(0);
        verify(mockPrintStream).println("Skipping findbugs analysis");
    }

    @Test
    public void findbugsEmpty() throws Exception{
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        when(mockStep.getFindbugsFilePattern()).thenReturn("");

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(0);
        verify(mockPrintStream).println("Skipping findbugs analysis");
    }

    @Test
    public void doesCheckstyle() throws Throwable {
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        when(mockStep.getCheckstyleFilePattern()).thenReturn("**/target/checkstyle-report.xml");
        FilePath[] files = new FilePath[]{new FilePath(new File(this.getClass().getResource("/checkstyle-report.xml").toURI()))};

        when(mockChannel.call(any(Callable.class))).thenReturn(files);

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(4);
        verify(mockPrintStream).println("Analysing 1 checkstyle file(s)");
    }

    @Test
    public void checkstyleNull() throws Exception{
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        when(mockStep.getCheckstyleFilePattern()).thenReturn(null);

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(0);
        verify(mockPrintStream).println("Skipping checkstyle analysis");
    }

    @Test
    public void checkstyleEmpty() throws Exception{
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        when(mockStep.getCheckstyleFilePattern()).thenReturn("");

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(0);
        verify(mockPrintStream).println("Skipping checkstyle analysis");
    }

    @Test
    public void doesJacoco() throws Throwable {
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        when(mockStep.getJacocoFilePattern()).thenReturn("**/target/jacoco.xml");
        FilePath[] files = new FilePath[]{new FilePath(new File(this.getClass().getResource("/jacoco.xml").toURI()))};

        when(mockChannel.call(any(Callable.class))).thenReturn(files);

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(6);
        verify(mockPrintStream).println("Analysing 1 jacoco file(s)");
    }

    @Test
    public void jacocoNull() throws Exception{
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        when(mockStep.getJacocoFilePattern()).thenReturn(null);

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(0);
        verify(mockPrintStream).println("Skipping jacoco analysis");
    }

    @Test
    public void jacocoEmpty() throws Exception{
        this.expectationsForBuildJob(HttpStatus.SC_CREATED);

        when(mockStep.getJacocoFilePattern()).thenReturn("");

        subject.run();

        ArgumentCaptor<CodeQualityCreateRequest> captor = ArgumentCaptor.forClass(CodeQualityCreateRequest.class);
        verify(mockHygieiaService).publishSonarResults(captor.capture());

        assertThat(captor.getValue().getMetrics()).hasSize(0);
        verify(mockPrintStream).println("Skipping jacoco analysis");
    }


}
