package com.ddd.d3.judge.adapter.judge0;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ddd.d3.judge.domain.JudgeExecutionResult;
import com.ddd.d3.judge.domain.JudgeLanguage;
import com.ddd.d3.judge.domain.JudgeStatus;
import com.ddd.d3.judge.domain.SubmissionCommand;
import com.ddd.d3.judge.domain.SubmissionMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "D3_JUDGE0_LIVE_SMOKE", matches = "true")
class Judge0PrivateRouteSmokeTest {

    private static final URI PRIVATE_JUDGE0_URI = URI.create("http://172.30.0.112:2358");
    private static final Map<JudgeLanguage, String> ACCEPTED_SOURCES = Map.of(
            JudgeLanguage.C,
            "#include <stdio.h>\nint main(void){int n; long long x,s=0; if(scanf(\"%d\",&n)!=1)return 1; while(n--&&scanf(\"%lld\",&x)==1)s+=x; printf(\"%lld\\n\",s);}",
            JudgeLanguage.CPP,
            "#include <iostream>\nint main(){int n; long long x,s=0; if(!(std::cin>>n))return 1; while(n--&&std::cin>>x)s+=x; std::cout<<s<<'\\n';}",
            JudgeLanguage.JAVA,
            "import java.util.*; class Main { public static void main(String[] a){ Scanner s=new Scanner(System.in); int n=s.nextInt(); long v=0; while(n-->0)v+=s.nextLong(); System.out.println(v); } }",
            JudgeLanguage.PYTHON3,
            "import sys\na=list(map(int,sys.stdin.buffer.read().split()))\nprint(sum(a[1:1+a[0]]))",
            JudgeLanguage.JAVASCRIPT,
            "const a=require('fs').readFileSync(0,'utf8').trim().split(/\\s+/).map(Number); console.log(a.slice(1,1+a[0]).reduce((x,y)=>x+y,0));",
            JudgeLanguage.TYPESCRIPT,
            "const a:number[]=require('fs').readFileSync(0,'utf8').trim().split(/\\s+/).map(Number); console.log(a.slice(1,1+a[0]).reduce((x,y)=>x+y,0));");

    @Test
    void d3Jdg001ExecutesEveryPinnedRuntimeThroughThePrivateApplicationRoute() {
        assertEquals(PRIVATE_JUDGE0_URI, configuredBaseUri());
        Judge0ExecutionAdapter adapter = adapter();

        for (JudgeLanguage language : JudgeLanguage.values()) {
            assertTrue(adapter.isAvailable(language), () -> language + " must be available");
            JudgeExecutionResult result = adapter.execute(command(language));
            assertEquals(JudgeStatus.ACCEPTED, result.status(), () -> language + " must be accepted");
            assertEquals(1, result.passedCount(), () -> language + " must pass the public case");
            assertEquals(1, result.totalCount(), () -> language + " must run exactly the public case");
            assertEquals("judge0-ce-1.13.1", result.adapterVersion());
            assertTrue(result.runtimeVersion() != null && !result.runtimeVersion().isBlank());
            assertTrue(result.runtimeMeasurements().isEmpty(), "RUN must not execute performance cases");
        }
    }

    private static Judge0ExecutionAdapter adapter() {
        Judge0HttpSettings settings = new Judge0HttpSettings(
                configuredBaseUri(),
                configuredBaseUri(),
                requiredEnvironment("JUDGE0_AUTH_HEADER"),
                requiredEnvironment("JUDGE0_AUTH_TOKEN"),
                Duration.ofSeconds(10),
                Duration.ofMillis(100),
                Duration.ofSeconds(30));
        Judge0Client client = new HttpJudge0Client(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(),
                settings);
        return new Judge0ExecutionAdapter(client, new DemoJudgeProblemCatalog(), Clock.systemUTC());
    }

    private static SubmissionCommand command(JudgeLanguage language) {
        return new SubmissionCommand(
                UUID.randomUUID(),
                UUID.fromString("00000000-0000-4000-8000-000000000059"),
                null,
                DemoJudgeProblemCatalog.DEMO_SUM_PROBLEM_ID,
                1,
                SubmissionMode.RUN,
                language,
                ACCEPTED_SOURCES.get(language),
                null,
                "issue-59-private-route-" + language.name().toLowerCase());
    }

    private static URI configuredBaseUri() {
        return URI.create(requiredEnvironment("JUDGE0_BASE_URL"));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the live smoke");
        }
        return value;
    }
}
