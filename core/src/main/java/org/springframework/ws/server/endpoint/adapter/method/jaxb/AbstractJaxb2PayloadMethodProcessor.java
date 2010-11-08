/*
 * Copyright 2005-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ws.server.endpoint.adapter.method.jaxb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.JAXBIntrospector;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;

import org.springframework.util.Assert;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.endpoint.adapter.method.AbstractPayloadMethodProcessor;
import org.springframework.ws.stream.StreamingPayload;
import org.springframework.ws.stream.StreamingWebServiceMessage;
import org.springframework.xml.transform.TraxUtils;

import org.w3c.dom.Node;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;

/**
 * Abstract base class for {@link org.springframework.ws.server.endpoint.adapter.method.MethodArgumentResolver
 * MethodArgumentResolver} and {@link org.springframework.ws.server.endpoint.adapter.method.MethodReturnValueHandler
 * MethodReturnValueHandler} implementations that use JAXB2. Creates {@link JAXBContext} object lazily, and offers
 * {@linkplain #marshalToResponsePayload(org.springframework.ws.context.MessageContext, Class, Object) marshalling} and
 * {@linkplain #unmarshalFromRequestPayload(org.springframework.ws.context.MessageContext, Class) unmarshalling}
 * methods.
 *
 * @author Arjen Poutsma
 * @since 2.0
 */
public abstract class AbstractJaxb2PayloadMethodProcessor extends AbstractPayloadMethodProcessor {

    private final ConcurrentMap<Class, JAXBContext> jaxbContexts = new ConcurrentHashMap<Class, JAXBContext>();

    /**
     * Marshals the given {@code jaxbElement} to the response payload of the given message context.
     *
     * @param messageContext the message context to marshal to
     * @param clazz          the clazz to create a marshaller for
     * @param jaxbElement    the object to be marshalled
     * @throws JAXBException in case of JAXB2 errors
     */
    protected final void marshalToResponsePayload(MessageContext messageContext, Class<?> clazz, Object jaxbElement)
            throws JAXBException {
        Assert.notNull(messageContext, "'messageContext' must not be null");
        Assert.notNull(clazz, "'clazz' must not be null");
        Assert.notNull(jaxbElement, "'jaxbElement' must not be null");
        if (logger.isDebugEnabled()) {
            logger.debug("Marshalling [" + jaxbElement + "] to response payload");
        }
        WebServiceMessage response = messageContext.getResponse();
        if (response instanceof StreamingWebServiceMessage) {
            StreamingWebServiceMessage streamingResponse = (StreamingWebServiceMessage) response;

            StreamingPayload payload = new JaxbStreamingPayload(clazz, jaxbElement);
            streamingResponse.setStreamingPayload(payload);
        }
        else {
            Result responsePayload = response.getPayloadResult();
            try {
                Jaxb2ResultCallback callback = new Jaxb2ResultCallback(clazz, jaxbElement);
                TraxUtils.doWithResult(responsePayload, callback);
            }
            catch (Exception ex) {
                throw convertToJaxbException(ex);
            }
        }
    }

    /**
     * Unmarshals the request payload of the given message context.
     *
     * @param messageContext the message context to unmarshal from
     * @param clazz          the class to unmarshal
     * @return the unmarshalled object, or {@code null} if the request has no payload
     * @throws JAXBException in case of JAXB2 errors
     */
    protected final Object unmarshalFromRequestPayload(MessageContext messageContext, Class<?> clazz)
            throws JAXBException {
        Source requestPayload = getRequestPayload(messageContext);
        if (requestPayload == null) {
            return null;
        }
        try {
            Jaxb2SourceCallback callback = new Jaxb2SourceCallback(clazz);
            TraxUtils.doWithSource(requestPayload, callback);
            if (logger.isDebugEnabled()) {
                logger.debug("Unmarshalled payload request to [" + callback.result + "]");
            }
            return callback.result;
        }
        catch (Exception ex) {
            throw convertToJaxbException(ex);
        }
    }

    /**
     * Unmarshals the request payload of the given message context as {@link JAXBElement}.
     *
     * @param messageContext the message context to unmarshal from
     * @param clazz          the class to unmarshal
     * @return the unmarshalled element, or {@code null} if the request has no payload
     * @throws JAXBException in case of JAXB2 errors
     */
    protected final <T> JAXBElement<T> unmarshalElementFromRequestPayload(MessageContext messageContext, Class<T> clazz)
            throws JAXBException {
        Source requestPayload = getRequestPayload(messageContext);
        if (requestPayload == null) {
            return null;
        }
        try {
            JaxbElementSourceCallback<T> callback = new JaxbElementSourceCallback<T>(clazz);
            TraxUtils.doWithSource(requestPayload, callback);
            if (logger.isDebugEnabled()) {
                logger.debug("Unmarshalled payload request to [" + callback.result + "]");
            }
            return callback.result;
        }
        catch (Exception ex) {
            throw convertToJaxbException(ex);
        }
    }

    private Source getRequestPayload(MessageContext messageContext) {
        WebServiceMessage request = messageContext.getRequest();
        return request != null ? request.getPayloadSource() : null;
    }

    private JAXBException convertToJaxbException(Exception ex) {
        if (ex instanceof JAXBException) {
            return (JAXBException) ex;
        }
        else {
            return new JAXBException(ex);
        }
    }

    private Marshaller createMarshaller(Class<?> clazz) throws JAXBException {
        JAXBContext jaxbContext = getJaxbContext(clazz);
        return jaxbContext.createMarshaller();
    }

    private Unmarshaller createUnmarshaller(Class<?> clazz) throws JAXBException {
        JAXBContext jaxbContext = getJaxbContext(clazz);
        return jaxbContext.createUnmarshaller();
    }

    private JAXBContext getJaxbContext(Class<?> clazz) throws JAXBException {
        Assert.notNull(clazz, "'clazz' must not be null");
        JAXBContext jaxbContext = jaxbContexts.get(clazz);
        if (jaxbContext == null) {
            jaxbContext = JAXBContext.newInstance(clazz);
            jaxbContexts.putIfAbsent(clazz, jaxbContext);
        }
        return jaxbContext;
    }

    // Callbacks

    private class Jaxb2SourceCallback implements TraxUtils.SourceCallback {

        private final Unmarshaller unmarshaller;

        private Object result;

        public Jaxb2SourceCallback(Class<?> clazz) throws JAXBException {
            this.unmarshaller = createUnmarshaller(clazz);
        }

        public void domSource(Node node) throws JAXBException {
            result = unmarshaller.unmarshal(node);
        }

        public void saxSource(XMLReader reader, InputSource inputSource) throws JAXBException {
            result = unmarshaller.unmarshal(inputSource);
        }

        public void staxSource(XMLEventReader eventReader) throws JAXBException {
            result = unmarshaller.unmarshal(eventReader);
        }

        public void staxSource(XMLStreamReader streamReader) throws JAXBException {
            result = unmarshaller.unmarshal(streamReader);
        }

        public void streamSource(InputStream inputStream) throws IOException, JAXBException {
            result = unmarshaller.unmarshal(inputStream);
        }

        public void streamSource(Reader reader) throws IOException, JAXBException {
            result = unmarshaller.unmarshal(reader);
        }
    }

    private class JaxbElementSourceCallback<T> implements TraxUtils.SourceCallback {

        private final Unmarshaller unmarshaller;

        private final Class<T> declaredType;

        private JAXBElement<T> result;

        public JaxbElementSourceCallback(Class<T> declaredType) throws JAXBException {
            this.unmarshaller = createUnmarshaller(declaredType);
            this.declaredType = declaredType;
        }

        public void domSource(Node node) throws JAXBException {
            result = unmarshaller.unmarshal(node, declaredType);
        }

        public void saxSource(XMLReader reader, InputSource inputSource) throws JAXBException {
            result = unmarshaller.unmarshal(new SAXSource(reader, inputSource), declaredType);
        }

        public void staxSource(XMLEventReader eventReader) throws JAXBException {
            result = unmarshaller.unmarshal(eventReader, declaredType);
        }

        public void staxSource(XMLStreamReader streamReader) throws JAXBException {
            result = unmarshaller.unmarshal(streamReader, declaredType);
        }

        public void streamSource(InputStream inputStream) throws IOException, JAXBException {
            result = unmarshaller.unmarshal(new StreamSource(inputStream), declaredType);
        }

        public void streamSource(Reader reader) throws IOException, JAXBException {
            result = unmarshaller.unmarshal(new StreamSource(reader), declaredType);
        }
    }

    private class Jaxb2ResultCallback implements TraxUtils.ResultCallback {

        private final Marshaller marshaller;

        private final Object jaxbElement;

        private Jaxb2ResultCallback(Class<?> clazz, Object jaxbElement) throws JAXBException {
            this.marshaller = createMarshaller(clazz);
            this.jaxbElement = jaxbElement;
        }

        public void domResult(Node node) throws JAXBException {
            marshaller.marshal(jaxbElement, node);
        }

        public void saxResult(ContentHandler contentHandler, LexicalHandler lexicalHandler) throws JAXBException {
            marshaller.marshal(jaxbElement, contentHandler);
        }

        public void staxResult(XMLEventWriter eventWriter) throws JAXBException {
            marshaller.marshal(jaxbElement, eventWriter);
        }

        public void staxResult(XMLStreamWriter streamWriter) throws JAXBException {
            marshaller.marshal(jaxbElement, streamWriter);
        }

        public void streamResult(OutputStream outputStream) throws JAXBException {
            marshaller.marshal(jaxbElement, outputStream);
        }

        public void streamResult(Writer writer) throws JAXBException {
            marshaller.marshal(jaxbElement, writer);
        }
    }

    private class JaxbStreamingPayload implements StreamingPayload {

        private final Object jaxbElement;

        private final Marshaller marshaller;

        private final QName name;

        private JaxbStreamingPayload(Class<?> clazz, Object jaxbElement) throws JAXBException {
            JAXBContext jaxbContext = getJaxbContext(clazz);
            this.marshaller = jaxbContext.createMarshaller();
            this.jaxbElement = jaxbElement;
            JAXBIntrospector introspector = jaxbContext.createJAXBIntrospector();
            this.name = introspector.getElementName(jaxbElement);
        }

        public QName getName() {
            return name;
        }

        public void writeTo(XMLStreamWriter streamWriter) throws XMLStreamException {
            try {
                marshaller.marshal(jaxbElement, streamWriter);
            }
            catch (JAXBException ex) {
                throw new XMLStreamException("Could not marshal [" + jaxbElement + "]: " + ex.getMessage(), ex);
            }
        }
    }


}