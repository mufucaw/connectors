/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.camunda.connector.api.xml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import io.camunda.connector.document.jackson.DocumentReferenceModel.CamundaDocumentReferenceModel;
import io.camunda.connector.document.jackson.JacksonModuleDocumentDeserializer.DocumentModuleSettings;
import io.camunda.document.Document;
import io.camunda.document.factory.DocumentFactoryImpl;
import io.camunda.document.store.InMemoryDocumentStore;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Parity tests for {@link ConnectorsXmlObjectMapperSupplier}. Every behavior covered by
 * {@code ConnectorsObjectMapperSupplierTest} for JSON is reproduced here for XML payloads.
 */
class ConnectorsXmlObjectMapperSupplierTest {

    @Test
    void java8DatesShouldBeSupported() throws JsonProcessingException {
        var mapper = ConnectorsXmlObjectMapperSupplier.getCopy();
        var expected = Map.of("data", LocalDate.of(2024, 1, 1));

        // round-trip (XML string contains the correct value)
        String xml = mapper.writeValueAsString(expected);
        assertThat(xml).contains("<data>2024-01-01</data>");

        Map<String, LocalDate> actual =
                mapper.readValue(xml, new TypeReference<Map<String, LocalDate>>() {});
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void singlePrimitiveValueShouldBeAcceptedAsArray() throws JsonProcessingException {
        var mapper = ConnectorsXmlObjectMapperSupplier.getCopy();
        // a bare number node is valid XML for XmlMapper
        String xml = "<Integer>1</Integer>";
        int[] actual = mapper.readValue(xml, int[].class);
        assertThat(actual).isEqualTo(new int[] {1});
    }

    @Test
    void singleDocumentShouldBeAcceptedAsArray() throws JsonProcessingException {
        var mapper =
                ConnectorsXmlObjectMapperSupplier.getCopy(
                        new DocumentFactoryImpl(InMemoryDocumentStore.INSTANCE), DocumentModuleSettings.create());

        var docRef =
                new CamundaDocumentReferenceModel("default", UUID.randomUUID().toString(), "hash", null);
        String docXml = mapper.writeValueAsString(docRef);

        // provide ONE <documents> element although the field is List<Document>
        String xml =
                "<TestRecordWithDocumentList><documents>"
                        + docXml
                        + "</documents></TestRecordWithDocumentList>";

        TestRecordWithDocumentList actual =
                mapper.readValue(xml, TestRecordWithDocumentList.class);

        assertThat(actual.documents()).hasSize(1);
        assertThat(actual.documents().get(0).reference()).isEqualTo(docRef);
    }

    @Test
    void singleElementDocumentArrayShouldBeAcceptedAsObject() throws JsonProcessingException {
        var mapper =
                ConnectorsXmlObjectMapperSupplier.getCopy(
                        new DocumentFactoryImpl(InMemoryDocumentStore.INSTANCE), DocumentModuleSettings.create());

        var docRef =
                new CamundaDocumentReferenceModel("default", UUID.randomUUID().toString(), "hash", null);
        // Debug
        System.out.println("*** *** *** docRef: " + docRef);
        // /Debug
        String docXml = mapper.writeValueAsString(docRef);
        // Debug
        System.out.println("docXml: " + docXml);
        // /Debug

        // the POJO expects a single Document, but we wrap it in a list-style container
        String xml =
                "<TestRecordWithDocument><document>"
                        + docXml
                        + "</document></TestRecordWithDocument>";

        TestRecordWithDocument actual = mapper.readValue(xml, TestRecordWithDocument.class);
        // Debug
        System.out.println("actual: " + actual.document().reference());
        System.out.println("expect: " + docRef);
        // /Debug
        assertThat(actual.document()).isNotNull();
        assertThat(actual.document().reference()).isEqualTo(docRef);
    }

    @Test
    void multipleElementDocumentArrayShouldNotBeAcceptedAsObject() throws JsonProcessingException {
        var mapper =
                ConnectorsXmlObjectMapperSupplier.getCopy(
                        new DocumentFactoryImpl(InMemoryDocumentStore.INSTANCE), DocumentModuleSettings.create());

        var docRef =
                new CamundaDocumentReferenceModel("default", UUID.randomUUID().toString(), "hash", null);
        String docXml = mapper.writeValueAsString(docRef);

        String xml =
                "<TestRecordWithDocument><document>"
                        + docXml
                        + docXml
                        + "</document></TestRecordWithDocument>";

        assertThatThrownBy(() -> mapper.readValue(xml, TestRecordWithDocument.class))
                .isInstanceOf(JsonMappingException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void singleElementStringArrayBindingToObject() throws JsonProcessingException {
        var mapper = ConnectorsXmlObjectMapperSupplier.getCopy();

        String xml = "<TestRecordWithString><value>hey</value></TestRecordWithString>";
        TestRecordWithString actual = mapper.readValue(xml, TestRecordWithString.class);
        assertThat(actual.value).isEqualTo("hey");
    }

    @Test
    void multipleElementStringArrayShouldNotBindToObject() throws JsonProcessingException {
        var mapper = ConnectorsXmlObjectMapperSupplier.getCopy();

        String xml =
                "<TestRecordWithString><value>hey</value><value>yo</value></TestRecordWithString>";
        assertThatThrownBy(() -> mapper.readValue(xml, TestRecordWithString.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void intrinsicFunctionShouldBeDeserialized() throws JsonProcessingException {
        var mapper =
                ConnectorsXmlObjectMapperSupplier.getCopy(
                        new DocumentFactoryImpl(InMemoryDocumentStore.INSTANCE), DocumentModuleSettings.create());

        String xml =
                """
                <TestRecordWithString>
                  <value>
                    <camunda.function.type>base64</camunda.function.type>
                    <params>hello</params>
                  </value>
                </TestRecordWithString>
                """;

        TestRecordWithString actual = mapper.readValue(xml, TestRecordWithString.class);
        assertThat(actual.value())
                .isEqualTo(Base64.getEncoder().encodeToString("hello".getBytes()));
    }

    private record TestRecordWithDocumentList(List<Document> documents) {}

    private record TestRecordWithDocument(Document document) {}

    private record TestRecordWithString(String value) {}
}