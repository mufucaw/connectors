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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.camunda.connector.document.jackson.JacksonModuleDocumentDeserializer;
import io.camunda.connector.document.jackson.JacksonModuleDocumentDeserializer.DocumentModuleSettings;
import io.camunda.connector.document.jackson.JacksonModuleDocumentSerializer;
import io.camunda.connector.document.jackson.deserializer.DocumentDeserializer;
import io.camunda.connector.feel.jackson.JacksonModuleFeelFunction;
import io.camunda.document.Document;
import io.camunda.document.factory.DocumentFactory;
import io.camunda.intrinsic.DefaultIntrinsicFunctionExecutor;
import io.camunda.intrinsic.IntrinsicFunctionExecutor;
import java.io.IOException;

/** XML-specific ObjectMapper supplier for the Connector runtime. */
public final class ConnectorsXmlObjectMapperSupplier {

  /** Base mapper cloned per request to keep thread-safety and per-call tweaks. */
  private static final ObjectMapper DEFAULT_MAPPER =
          XmlMapper.builder()
                  .defaultUseWrapper(false)
                  .addModules(
                          new JacksonModuleFeelFunction(),
                          new Jdk8Module(),
                          new JavaTimeModule(),
                          new JacksonModuleDocumentSerializer())
                  .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                  .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                  .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                  .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                  .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                  .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                  .enable(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS)
                  .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
                  .build();

  private ConnectorsXmlObjectMapperSupplier() {}

  /** Clone without document-specific modules. */
  public static ObjectMapper getCopy() {
    return DEFAULT_MAPPER.copy();
  }

  /** Clone and register runtime-specific document (binary/FEEL) support. */
  public static ObjectMapper getCopy(
          final DocumentFactory factory, final DocumentModuleSettings settings) {

    final ObjectMapper copy = DEFAULT_MAPPER.copy();
    final IntrinsicFunctionExecutor exec = new DefaultIntrinsicFunctionExecutor(copy);

    // original module
    copy.registerModule(new JacksonModuleDocumentDeserializer(factory, exec, settings));
    // override that unwraps the <CamundaDocumentReferenceModel> XML wrapper
    copy.registerModule(buildWrapperAwareModule(factory, exec, settings));

    return copy;
  }

  // helper that builds the override module
  private static Module buildWrapperAwareModule(
          DocumentFactory factory,
          IntrinsicFunctionExecutor exec,
          DocumentModuleSettings settings) {

    SimpleModule module =
            new SimpleModule("xml-document-wrapper", new Version(1, 0, 0, null, null, null));

    module.addDeserializer(
            Document.class, new WrapperAwareDocumentDeserializer(factory, exec, settings));

    return module;
  }

  // the “unwrap-and-delegate” deserializer
  private static final class WrapperAwareDocumentDeserializer extends JsonDeserializer<Document> {

    private final DocumentDeserializer delegate;

    WrapperAwareDocumentDeserializer(
            DocumentFactory factory, IntrinsicFunctionExecutor exec, DocumentModuleSettings settings) {
      this.delegate = new DocumentDeserializer(factory, exec, settings);
    }

    @Override
    public Document deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
      JsonNode node = p.getCodec().readTree(p);

      // unwrap <CamundaDocumentReferenceModel> if it is the *only* child
      if (node.isObject() && node.size() == 1) {
        node = ((ObjectNode) node).elements().next();
      }

      // delegate to the original logic
      return delegate.deserialize(node.traverse(p.getCodec()), ctx);
    }
  }
}