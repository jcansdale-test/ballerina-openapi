 import ballerina/http;

 listener http:Listener helloEp = new (9090);
 type Pet record {
    int id;
    string name;
    string tag?;
 };
 service /payloadV on helloEp {
     resource function post hi(http:Caller caller, http:Request request, @http:Payload { mediaType:
     ["application/json"] } Pet payload) {

     }
 }

