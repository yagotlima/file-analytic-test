package br.com.file.analytic.report;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Apache Camel route builder that watches changes on the input directory and updates
 * files in the output directory accordingly.
 * <p>
 * When a file is created or updated inside the input directory, a file with the same name
 * is created or updated inside the output directory with it's report in JSON format.
 */
@Component
public class ReportRouteBuilder extends RouteBuilder implements InitializingBean {
    private static final String REPORT_FILE_ENDPOINT = "seda:reportFile";
    private static final String DELETE_REPORT_ENDPOINT = "seda:deleteReport";

    @Value("${inputDir}")
    private String inputDir;
    @Value("${outputDir}")
    private String outputDir;

    private Path inputPath;
    private Path outputPath;

    @Autowired
    private ReportService reportService;

    @Override
    public void afterPropertiesSet() throws Exception {
        this.inputPath = initDir(inputDir);
        this.outputPath = initDir(outputDir);
    }

    @Override
    public void configure() throws Exception {
        from("file-watch://" + inputPath.toString())
                .choice()
                .when(header("CamelFileEventType").isEqualTo("CREATE"))
                .to(REPORT_FILE_ENDPOINT)
                .when(header("CamelFileEventType").isEqualTo("UPDATE"))
                .to(REPORT_FILE_ENDPOINT)
                .when(header("CamelFileEventType").isEqualTo("DELETE"))
                .to(DELETE_REPORT_ENDPOINT)
                .otherwise()
                .to("log:invalidEventError");

        from(REPORT_FILE_ENDPOINT)
                .process(new ReportProcessor(reportService))
                .marshal().json(JsonLibrary.Jackson)
                .to("file:" + outputPath)
                .to("stream:out");

        from(DELETE_REPORT_ENDPOINT).process(exchange -> {
            File inputFile = exchange.getMessage().getBody(File.class);
            Path outputFile = outputPath.resolve(inputFile.getName());
            Files.delete(outputFile);

            exchange.getMessage().setBody(outputFile.toString() + " deleted");
        }).to("stream:out");
    }

    public Path getInputPath() {
        return inputPath;
    }

    public Path getOutputPath() {
        return outputPath;
    }

    private Path initDir(String dir) throws IOException, IllegalArgumentException {
        return initDir(Paths.get(dir));
    }

    private Path initDir(Path dir) throws IOException, IllegalArgumentException {
        if (Files.exists(dir) && !Files.isDirectory(dir)) {
            throw new IllegalArgumentException("The input and output paths must not be of a file.");
        }

        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        return dir;
    }

}
