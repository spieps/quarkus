package io.quarkus.resteasy.reactive.server.test.security.inheritance.classpermitall;

import static io.quarkus.resteasy.reactive.server.test.security.inheritance.SubPaths.CLASS_PATH_ON_RESOURCE;
import static io.quarkus.resteasy.reactive.server.test.security.inheritance.SubPaths.CLASS_PERMIT_ALL_METHOD_PERMIT_ALL_PATH;
import static io.quarkus.resteasy.reactive.server.test.security.inheritance.SubPaths.CLASS_PERMIT_ALL_PATH;
import static io.quarkus.resteasy.reactive.server.test.security.inheritance.SubPaths.IMPL_METHOD_WITH_PATH;
import static io.quarkus.resteasy.reactive.server.test.security.inheritance.SubPaths.IMPL_ON_INTERFACE;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import io.vertx.core.json.JsonObject;

@PermitAll
public interface ClassPermitAllInterfaceWithoutPath_SecurityOnInterface {

    @POST
    @Path(CLASS_PATH_ON_RESOURCE + IMPL_ON_INTERFACE + IMPL_METHOD_WITH_PATH + CLASS_PERMIT_ALL_PATH)
    default String classPathOnResource_ImplOnInterface_ImplMethodWithPath_ClassPermitAll(JsonObject array) {
        return CLASS_PATH_ON_RESOURCE + IMPL_ON_INTERFACE + IMPL_METHOD_WITH_PATH + CLASS_PERMIT_ALL_PATH;
    }

    @PermitAll
    @POST
    @Path(CLASS_PATH_ON_RESOURCE + IMPL_ON_INTERFACE + IMPL_METHOD_WITH_PATH + CLASS_PERMIT_ALL_METHOD_PERMIT_ALL_PATH)
    default String classPathOnResource_ImplOnInterface_ImplMethodWithPath_ClassPermitAllMethodPermitAll(JsonObject array) {
        return CLASS_PATH_ON_RESOURCE + IMPL_ON_INTERFACE + IMPL_METHOD_WITH_PATH + CLASS_PERMIT_ALL_METHOD_PERMIT_ALL_PATH;
    }

}
