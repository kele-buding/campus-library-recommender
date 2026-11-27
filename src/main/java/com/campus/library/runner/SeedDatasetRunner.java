package com.campus.library.runner;

import com.campus.library.config.AppProperties;
import com.campus.library.service.SeedDatasetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 支持通过启动参数触发初始化数据装载：
 *   mvn spring-boot:run -Dspring-boot.run.arguments="--seed"
 *   mvn spring-boot:run -Dspring-boot.run.arguments="--seed-reset"
 */
@Component
public class SeedDatasetRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDatasetRunner.class);

    private final SeedDatasetService seedDatasetService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public SeedDatasetRunner(
            SeedDatasetService seedDatasetService,
            AppProperties appProperties,
            ObjectMapper objectMapper
    ) {
        this.seedDatasetService = seedDatasetService;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean reset = args.containsOption("seed-reset");
        boolean seed = args.containsOption("seed") || appProperties.getSeed().isAutoOnStartup();
        if (!seed && !reset) {
            return;
        }
        SeedDatasetService.SeedResult result = seedDatasetService.seedDataset(reset);
        log.info("seed_dataset 完成:\n{}",
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
}
