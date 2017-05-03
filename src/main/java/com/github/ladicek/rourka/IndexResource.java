package com.github.ladicek.rourka;

import com.github.ladicek.rourka.ci.BuildResult;
import com.github.ladicek.rourka.ci.BuildStatus;
import com.github.ladicek.rourka.ci.PipelineDescription;
import com.github.ladicek.rourka.ci.PipelineType;
import com.github.ladicek.rourka.openshift.OpenShift;
import com.github.ladicek.rourka.thymeleaf.View;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.client.OpenShiftClient;

import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonObject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.io.StringReader;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Path("/")
public class IndexResource {
    @Inject
    private OpenShift openshift;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public View get() {
        try (OpenShiftClient oc = openshift.createClient()) {
            List<BuildConfig> buildConfigs = oc.buildConfigs()
                    .list()
                    .getItems()
                    .stream()
                    .filter(bc -> bc.getMetadata().getAnnotations() != null
                            && bc.getMetadata().getAnnotations().containsKey("ci/type")
                            && bc.getMetadata().getAnnotations().containsKey("ci/description"))
                    .sorted(Comparator.comparing(bc -> bc.getMetadata().getAnnotations().get("ci/description")))
                    .collect(Collectors.toList());

            List<PipelineType> types = buildConfigs.stream()
                    .map(bc -> bc.getMetadata().getAnnotations().get("ci/type"))
                    .distinct()
                    .sorted()
                    .map(PipelineType::new)
                    .collect(Collectors.toList());

            List<Build> builds = oc.builds()
                    .withLabelIn("openshift.io/build-config.name", buildConfigs.stream()
                            .map(bc -> bc.getMetadata().getName())
                            .toArray(String[]::new))
                    .list()
                    .getItems();

            Map<PipelineDescription, Map<PipelineType, BuildResult>> table = new LinkedHashMap<>();
            for (BuildConfig buildConfig : buildConfigs) {
                String name = buildConfig.getMetadata().getName();

                BuildResult result = builds.stream()
                        .filter(b -> name.equals(b.getMetadata().getLabels().get("openshift.io/build-config.name")))
                        .filter(b -> b.getStatus().getCompletionTimestamp() != null)
                        .max(Comparator.comparingInt(b -> Integer.parseInt(b.getMetadata().getAnnotations().get("openshift.io/build.number"))))
                        .map(IndexResource::getBuildResult)
                        .orElse(new BuildResult(BuildStatus.UNKNOWN, null, null));

                PipelineDescription desc = new PipelineDescription(buildConfig.getMetadata().getAnnotations().get("ci/description"));
                PipelineType type = new PipelineType(buildConfig.getMetadata().getAnnotations().get("ci/type"));
                table.computeIfAbsent(desc, ignored -> new LinkedHashMap<>()).put(type, result);
            }

            return new View("index.html", "table", table, "header", types);
        }
    }

    private static BuildResult getBuildResult(Build build) {
        BuildStatus status;
        String statusJsonString = build.getMetadata().getAnnotations().get("openshift.io/jenkins-status-json");
        JsonObject statusJson = Json.createReader(new StringReader(statusJsonString)).readObject();
        String statusString = statusJson.getString("status");
        switch (statusString) {
            case "SUCCESS":
                status = BuildStatus.PASS;
                break;
            case "FAILED":
                status = BuildStatus.FAIL;
                break;
            default:
                status = BuildStatus.UNKNOWN;
        }
        String name = statusJson.getString("name");
        String link = build.getMetadata().getAnnotations().get("openshift.io/jenkins-build-uri");
        return new BuildResult(status, name, link);
    }
}
