import ballerina/http;
import ballerina/openapi;

listener http:Listener ep0 = new(80, config = {host: "petstore.openapi.io"});

listener http:Listener ep1 = new(443, config = {host: "petstore.swagger.io"});

@openapi:ServiceInfo {
    contract: "../../swagger/invalid/petstoreParameterTM.yaml",
    tags: [ ]
}

service v1 on ep0, ep1 {

    resource function get pets (http:Caller caller, http:Request req) returns error? {

    }

    resource function get pets/[int petId] (http:Caller caller, http:Request req) returns error? {

    }

}
