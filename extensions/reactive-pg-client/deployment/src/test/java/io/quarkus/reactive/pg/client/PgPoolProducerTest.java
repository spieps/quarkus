package io.quarkus.reactive.pg.client;

import java.util.concurrent.CompletionStage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vertx.sqlclient.Pool;

public class PgPoolProducerTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withConfigurationResource("application-default-datasource.properties")
            .withApplicationRoot((jar) -> jar
                    .addClasses(BeanUsingBarePgClient.class)
                    .addClass(BeanUsingMutinyPgClient.class));

    @Inject
    BeanUsingBarePgClient beanUsingBare;

    @Inject
    BeanUsingMutinyPgClient beanUsingMutiny;

    @Test
    public void testVertxInjection() {
        beanUsingBare.verify()
                .thenCompose(v -> beanUsingMutiny.verify())
                .toCompletableFuture()
                .join();
    }

    @ApplicationScoped
    static class BeanUsingBarePgClient {

        @Inject
        Pool pgClient;

        public CompletionStage<?> verify() {
            return pgClient.query("SELECT 1").execute().toCompletionStage();
        }
    }

    @ApplicationScoped
    static class BeanUsingMutinyPgClient {

        @Inject
        io.vertx.mutiny.sqlclient.Pool pgClient;

        public CompletionStage<Void> verify() {
            return pgClient.query("SELECT 1").execute()
                    .onItem().ignore().andContinueWithNull()
                    .subscribeAsCompletionStage();
        }
    }
}
