/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.generators;

import io.ballerina.compiler.syntax.tree.AbstractNodeFactory;
import io.ballerina.compiler.syntax.tree.ArrayTypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.IdentifierToken;
import io.ballerina.compiler.syntax.tree.ImportDeclarationNode;
import io.ballerina.compiler.syntax.tree.ModuleMemberDeclarationNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeFactory;
import io.ballerina.compiler.syntax.tree.NodeList;
import io.ballerina.compiler.syntax.tree.RecordFieldNode;
import io.ballerina.compiler.syntax.tree.RecordTypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.SyntaxTree;
import io.ballerina.compiler.syntax.tree.Token;
import io.ballerina.compiler.syntax.tree.TypeDefinitionNode;
import io.ballerina.compiler.syntax.tree.TypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.TypeReferenceNode;
import io.ballerina.openapi.exception.BallerinaOpenApiException;
import io.ballerina.tools.text.TextDocument;
import io.ballerina.tools.text.TextDocuments;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.ballerinalang.formatter.core.FormatterException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.ballerina.compiler.syntax.tree.NodeFactory.createBuiltinSimpleNameReferenceNode;
import static io.ballerina.generators.GeneratorUtils.convertOpenAPITypeToBallerina;
import static io.ballerina.generators.GeneratorUtils.escapeIdentifier;
import static io.ballerina.generators.GeneratorUtils.extractReferenceType;

/**
 *This class wraps the {@link Schema} from openapi models inorder to overcome complications
 *while populating syntax tree.
 */
public class BallerinaSchemaGenerator {
    private static final PrintStream outStream = System.err;

    public static SyntaxTree generateSyntaxTree(Path definitionPath)
            throws OpenApiException, FormatterException, IOException, BallerinaOpenApiException,
            BallerinaOpenApiException {
        OpenAPI openApi = parseOpenAPIFile(definitionPath.toString());
        // TypeDefinitionNodes their
        List<TypeDefinitionNode> typeDefinitionNodeList = new LinkedList<>();
        if (openApi.getComponents() != null) {
            //Create typeDefinitionNode
            Components components = openApi.getComponents();
            if (components.getSchemas() != null) {
                Map<String, Schema> schemas = components.getSchemas();
                for (Map.Entry<String, Schema> schema: schemas.entrySet()) {
                    List<String> required = schema.getValue().getRequired();

                    //1.typeKeyWord
                    Token typeKeyWord = AbstractNodeFactory.createIdentifierToken("public type");
                    //2.typeName
                    IdentifierToken typeName = AbstractNodeFactory.createIdentifierToken(
                            escapeIdentifier(schema.getKey().trim()));
                    //3.typeDescriptor - RecordTypeDescriptor
                    //3.1 recordKeyWord
                    Token recordKeyWord = AbstractNodeFactory.createIdentifierToken("record");
                    //3.2 bodyStartDelimiter
                    Token bodyStartDelimiter = AbstractNodeFactory.createIdentifierToken("{");
                    //3.3 fields
                    //Generate RecordFiled
                    List<Node> recordFieldList = new ArrayList<>();
                    Schema schemaValue = schema.getValue();
                    if (schemaValue instanceof ComposedSchema) {
                        ComposedSchema composedSchema = (ComposedSchema) schemaValue;
                        if (composedSchema.getAllOf() != null) {
                            List<Schema> allOf = composedSchema.getAllOf();
                            for (Schema allOfschema: allOf) {
                                if (allOfschema.getType() == null && allOfschema.get$ref() != null) {
                                    //Generate typeReferenceNode
                                    Token typeRef =
                                            AbstractNodeFactory.createIdentifierToken(escapeIdentifier(
                                                    extractReferenceType(allOfschema.get$ref())));
                                    Token asterisk = AbstractNodeFactory.createIdentifierToken("*");
                                    Token semicolon = AbstractNodeFactory.createIdentifierToken(";");
                                    TypeReferenceNode recordField =
                                            NodeFactory.createTypeReferenceNode(asterisk, typeRef, semicolon);
                                    recordFieldList.add(recordField);
                                } else if (allOfschema instanceof ObjectSchema &&
                                        (allOfschema.getProperties() != null)) {
                                    Map<String, Schema> properties = allOfschema.getProperties();
                                    for (Map.Entry<String, Schema> field : properties.entrySet()) {
                                        addRecordFields(required, recordFieldList, field);
                                    }
                                }
                            }
                            NodeList<Node> fieldNodes = AbstractNodeFactory.createNodeList(recordFieldList);
                            Token bodyEndDelimiter = AbstractNodeFactory.createIdentifierToken("}");
                            RecordTypeDescriptorNode recordTypeDescriptorNode =
                                    NodeFactory.createRecordTypeDescriptorNode(recordKeyWord, bodyStartDelimiter,
                                            fieldNodes, null, bodyEndDelimiter);
                            Token semicolon = AbstractNodeFactory.createIdentifierToken(";");
                            TypeDefinitionNode typeDefinitionNode = NodeFactory.createTypeDefinitionNode(null,
                                    null, typeKeyWord, typeName, recordTypeDescriptorNode, semicolon);
                            typeDefinitionNodeList.add(typeDefinitionNode);
                        }
                    } else if (schema.getValue().getProperties() != null || (schema.getValue() instanceof ObjectSchema
                            && schema.getValue().getProperties() != null)) {
                        Map<String, Schema> fields = schema.getValue().getProperties();
                        if (fields != null) {
                            for (Map.Entry<String, Schema> field : fields.entrySet()) {
                                addRecordFields(required, recordFieldList, field);
                            }
                            NodeList<Node> fieldNodes = AbstractNodeFactory.createNodeList(recordFieldList);
                            Token bodyEndDelimiter = AbstractNodeFactory.createIdentifierToken("}");
                            RecordTypeDescriptorNode recordTypeDescriptorNode =
                                    NodeFactory.createRecordTypeDescriptorNode(recordKeyWord, bodyStartDelimiter,
                                            fieldNodes, null, bodyEndDelimiter);
                            Token semicolon = AbstractNodeFactory.createIdentifierToken(";");
                            TypeDefinitionNode typeDefinitionNode = NodeFactory.createTypeDefinitionNode(null,
                                    null, typeKeyWord, typeName, recordTypeDescriptorNode, semicolon);
                            typeDefinitionNodeList.add(typeDefinitionNode);
                        }
                    } else if (schema.getValue().getType().equals("array")) {
                        if (schemaValue instanceof ArraySchema) {
                            ArraySchema arraySchema = (ArraySchema) schemaValue;
                            Token openSBracketToken = AbstractNodeFactory.createIdentifierToken("[");
                            Token closeSBracketToken = AbstractNodeFactory.createIdentifierToken("]");
                            IdentifierToken fieldName =
                                    AbstractNodeFactory.createIdentifierToken(escapeIdentifier(
                                            schema.getKey().trim().toLowerCase(Locale.ENGLISH)) + "list");
                            Token semicolonToken = AbstractNodeFactory.createIdentifierToken(";");
                            TypeDescriptorNode fieldTypeName;
                            if (arraySchema.getItems() != null) {
                                //Generate RecordFiled
                                //FiledName
                                fieldTypeName = extractOpenApiSchema(arraySchema.getItems());
                            } else {
                                Token type =
                                        AbstractNodeFactory.createIdentifierToken("string ");
                                fieldTypeName =  NodeFactory.createBuiltinSimpleNameReferenceNode(null, type);
                            }
                            ArrayTypeDescriptorNode arrayField =
                                    NodeFactory.createArrayTypeDescriptorNode(fieldTypeName, openSBracketToken,
                                            null, closeSBracketToken);
                            RecordFieldNode recordFieldNode = NodeFactory.createRecordFieldNode(null,
                                    null, arrayField, fieldName, null, semicolonToken);
                            NodeList<Node> fieldNodes = AbstractNodeFactory.createNodeList(recordFieldNode);
                            Token bodyEndDelimiter = AbstractNodeFactory.createIdentifierToken("}");
                            RecordTypeDescriptorNode recordTypeDescriptorNode =
                                    NodeFactory.createRecordTypeDescriptorNode(recordKeyWord, bodyStartDelimiter,
                                            fieldNodes, null, bodyEndDelimiter);
                            Token semicolon = AbstractNodeFactory.createIdentifierToken(";");
                            TypeDefinitionNode typeDefinitionNode = NodeFactory.createTypeDefinitionNode(null,
                                    null, typeKeyWord, typeName, recordTypeDescriptorNode, semicolon);
                            typeDefinitionNodeList.add(typeDefinitionNode);
                        }
                    }
                }
            }
        }
        //Create imports
        NodeList<ImportDeclarationNode> imports = AbstractNodeFactory.createEmptyNodeList();
        // Create module member declaration
        NodeList<ModuleMemberDeclarationNode> moduleMembers =
                AbstractNodeFactory.createNodeList(typeDefinitionNodeList.toArray(
                        new TypeDefinitionNode[typeDefinitionNodeList.size()]));

        Token eofToken = AbstractNodeFactory.createIdentifierToken("");
        ModulePartNode modulePartNode = NodeFactory.createModulePartNode(imports, moduleMembers, eofToken);

        TextDocument textDocument = TextDocuments.from("");
        SyntaxTree syntaxTree = SyntaxTree.from(textDocument);
        return syntaxTree.modifyWith(modulePartNode);
    }

    /**
     * This util for generate record field with given schema properties.
     */
    private static void addRecordFields(List<String> required, List<Node> recordFieldList,
                                        Map.Entry<String, Schema> field) throws BallerinaOpenApiException {

        RecordFieldNode recordFieldNode;
        //FiledName
        IdentifierToken fieldName =
                AbstractNodeFactory.createIdentifierToken(escapeIdentifier(field.getKey().trim()));

        TypeDescriptorNode fieldTypeName = extractOpenApiSchema(field.getValue());
        Token semicolonToken = AbstractNodeFactory.createIdentifierToken(";");
        Token questionMarkToken = AbstractNodeFactory.createIdentifierToken("?");
        if (required != null) {
            if (!required.contains(field.getKey().trim())) {
                recordFieldNode = NodeFactory.createRecordFieldNode(null, null,
                        fieldTypeName, fieldName, questionMarkToken, semicolonToken);
            } else {
                recordFieldNode = NodeFactory.createRecordFieldNode(null, null,
                        fieldTypeName, fieldName, null, semicolonToken);
            }
        } else {
            recordFieldNode = NodeFactory.createRecordFieldNode(null, null,
                    fieldTypeName, fieldName, questionMarkToken, semicolonToken);
        }
        recordFieldList.add(recordFieldNode);
    }

    /**
     * Common method to extract OpenApi Schema type objects in to Ballerina type compatible schema objects.
     * @param schema - OpenApi Schema
     */
    private static TypeDescriptorNode extractOpenApiSchema(Schema schema) throws BallerinaOpenApiException {

        if (schema.getType() != null || schema.getProperties() != null) {
            if (schema.getType() != null && ((schema.getType().equals("integer") || schema.getType().equals("number"))
                    || schema.getType().equals("string") || schema.getType().equals("boolean"))) {
                String type = convertOpenAPITypeToBallerina(schema.getType().trim());
                if (schema.getType().equals("number")) {
                    if (schema.getFormat() != null) {
                        type = convertOpenAPITypeToBallerina(schema.getFormat().trim());
                    }
                }
                Token typeName = AbstractNodeFactory.createIdentifierToken(type);
                return createBuiltinSimpleNameReferenceNode(null, typeName);
            } else if (schema.getType() != null && schema.getType().equals("array")) {
                if (schema instanceof ArraySchema) {
                    final ArraySchema arraySchema = (ArraySchema) schema;

                    if (arraySchema.getItems() != null) {
                        //single array
                        Token openSBracketToken = AbstractNodeFactory.createIdentifierToken("[");
                        Token closeSBracketToken = AbstractNodeFactory.createIdentifierToken("]");
                        String type;
                        Token typeName;
                        TypeDescriptorNode memberTypeDesc;
                        Schema schemaItem = arraySchema.getItems();
                        if (schemaItem.get$ref() != null) {
                            type = extractReferenceType(arraySchema.getItems().get$ref());
                            typeName = AbstractNodeFactory.createIdentifierToken(type);
                            memberTypeDesc = createBuiltinSimpleNameReferenceNode(null, typeName);
                            return NodeFactory.createArrayTypeDescriptorNode(memberTypeDesc, openSBracketToken,
                                    null, closeSBracketToken);
                        } else if (schemaItem instanceof ArraySchema) {
                            memberTypeDesc = extractOpenApiSchema(arraySchema.getItems());
                            return NodeFactory.createArrayTypeDescriptorNode(memberTypeDesc, openSBracketToken,
                                    null, closeSBracketToken);
                        } else if (schemaItem instanceof ObjectSchema) {
                            ObjectSchema inlineSchema = (ObjectSchema) schemaItem;
                            memberTypeDesc = extractOpenApiSchema(inlineSchema);
                            return NodeFactory.createArrayTypeDescriptorNode(memberTypeDesc, openSBracketToken,
                                    null, closeSBracketToken);
                        } else if (schemaItem.getType() != null) {
                            type = schemaItem.getType();
                            typeName = AbstractNodeFactory.createIdentifierToken(convertOpenAPITypeToBallerina(type));
                            memberTypeDesc = createBuiltinSimpleNameReferenceNode(null, typeName);
                            return NodeFactory.createArrayTypeDescriptorNode(memberTypeDesc, openSBracketToken,
                                    null, closeSBracketToken);
                        } else {
                            typeName = AbstractNodeFactory.createIdentifierToken("anydata");
                            memberTypeDesc = createBuiltinSimpleNameReferenceNode(null, typeName);
                            return NodeFactory.createArrayTypeDescriptorNode(memberTypeDesc, openSBracketToken,
                                    null, closeSBracketToken);
                        }
                    }
                }
            } else if ((schema.getType() != null && schema.getType().equals("object")) &&
                    schema.getProperties() != null) {
                Map<String, Schema> properties = schema.getProperties();
                Token recordKeyWord = AbstractNodeFactory.createIdentifierToken("record ");
                Token bodyStartDelimiter = AbstractNodeFactory.createIdentifierToken("{ ");
                Token bodyEndDelimiter = AbstractNodeFactory.createIdentifierToken("} ");
                List<Node> recordFList = new ArrayList<>();
                List<String> required = schema.getRequired();
                for (Map.Entry<String, Schema> property: properties.entrySet()) {
                    addRecordFields(required, recordFList, property);
                }
                NodeList<Node> fieldNodes = AbstractNodeFactory.createNodeList(recordFList);

                return NodeFactory.createRecordTypeDescriptorNode(recordKeyWord, bodyStartDelimiter, fieldNodes, null
                        , bodyEndDelimiter);

            } else {
                outStream.println("Encountered an unsupported type. Type `anydata` would be used for the field.");
                Token typeName = AbstractNodeFactory.createIdentifierToken("anydata");
                return createBuiltinSimpleNameReferenceNode(null, typeName);
            }
        } else if (schema.get$ref() != null) {
            Token typeName = AbstractNodeFactory.createIdentifierToken(extractReferenceType(schema.get$ref()));
            return createBuiltinSimpleNameReferenceNode(null, typeName);
        } else {
            //This contains a fallback to Ballerina common type `any` if the OpenApi specification type is not defined
            // or not compatible with any of the current Ballerina types.
            outStream.println("Encountered an unsupported type. Type `anydata` would be used for the field.");
            Token typeName = AbstractNodeFactory.createIdentifierToken("anydata");
            return createBuiltinSimpleNameReferenceNode(null, typeName);
        }
        Token typeName = AbstractNodeFactory.createIdentifierToken("anydata");
        return createBuiltinSimpleNameReferenceNode(null, typeName);
    }

    /**
     * Parse and get the {@link OpenAPI} for the given OpenAPI contract.
     *
     * @param definitionURI     URI for the OpenAPI contract
     * @return {@link OpenAPI}  OpenAPI model
     * @throws OpenApiException in case of exception
     */
    public static OpenAPI parseOpenAPIFile(String definitionURI) throws OpenApiException, IOException {
        Path contractPath = Paths.get(definitionURI);
        if (!Files.exists(contractPath)) {
            throw new OpenApiException(ErrorMessages.invalidFilePath(definitionURI));
        }
        if (!(definitionURI.endsWith(".yaml") || definitionURI.endsWith(".json") || definitionURI.endsWith(".yml"))) {
            throw new OpenApiException(ErrorMessages.invalidFile());
        }
        String openAPIFileContent = Files.readString(Paths.get(definitionURI));
        SwaggerParseResult parseResult = new OpenAPIV3Parser().readContents(openAPIFileContent);
        if (!parseResult.getMessages().isEmpty()) {
            throw new OpenApiException(ErrorMessages.parserException(definitionURI));
        }
        return parseResult.getOpenAPI();
    }
}
