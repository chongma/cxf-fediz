/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.fediz.service.idp.metadata;

import java.security.cert.X509Certificate;
import java.util.Base64;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.w3c.dom.Document;
import org.apache.cxf.fediz.core.util.CertsUtils;
import org.apache.cxf.fediz.core.util.SignatureUtils;
import org.apache.cxf.fediz.service.idp.domain.Claim;
import org.apache.cxf.fediz.service.idp.domain.Idp;
import org.apache.cxf.staxutils.W3CDOMStreamWriter;
import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.util.DOM2Writer;
import org.apache.xml.security.stax.impl.util.IDGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.cxf.fediz.core.FedizConstants.SAML2_METADATA_NS;
import static org.apache.cxf.fediz.core.FedizConstants.SCHEMA_INSTANCE_NS;
import static org.apache.cxf.fediz.core.FedizConstants.WS_ADDRESSING_NS;
import static org.apache.cxf.fediz.core.FedizConstants.WS_FEDERATION_NS;

public class IdpMetadataWriter {

    private static final Logger LOG = LoggerFactory.getLogger(IdpMetadataWriter.class);

    public Document getMetaData(Idp config) {
        return getMetaData(config, false);
    }

    public Document getMetaData(Idp config, boolean saml) {
        try {
            //Return as text/xml
            Crypto crypto = CertsUtils.getCryptoFromFile(config.getCertificate());

            W3CDOMStreamWriter writer = new W3CDOMStreamWriter();

            writer.writeStartDocument("UTF-8", "1.0");

            String referenceID = IDGenerator.generateID("_");
            writer.writeStartElement("md", "EntityDescriptor", SAML2_METADATA_NS);
            writer.writeAttribute("ID", referenceID);

            writer.writeAttribute("entityID", config.getIdpUrl().toString());

            writer.writeNamespace("md", SAML2_METADATA_NS);
            writer.writeNamespace("xsi", SCHEMA_INSTANCE_NS);

            if (saml) {
                writeSAMLSSOMetadata(writer, config, crypto);
            } else {
                writeFederationMetadata(writer, config, crypto);
            }

            writer.writeEndElement(); // EntityDescriptor

            writer.writeEndDocument();

            writer.close();

            if (LOG.isDebugEnabled()) {
                String out = DOM2Writer.nodeToString(writer.getDocument());
                LOG.debug("***************** unsigned ****************");
                LOG.debug(out);
                LOG.debug("***************** unsigned ****************");
            }

            Document result = SignatureUtils.signMetaInfo(crypto, null, config.getCertificatePassword(),
                                                          writer.getDocument(), referenceID);
            if (result != null) {
                return result;
            } else {
                throw new RuntimeException("Failed to sign the metadata document: result=null");
            }
        } catch (Exception e) {
            LOG.error("Error creating service metadata information ", e);
            throw new RuntimeException("Error creating service metadata information: " + e.getMessage());
        }

    }

    private void writeFederationMetadata(
        XMLStreamWriter writer, Idp config, Crypto crypto
    ) throws XMLStreamException {

        writer.writeNamespace("fed", WS_FEDERATION_NS);
        writer.writeNamespace("wsa", WS_ADDRESSING_NS);
        writer.writeNamespace("auth", WS_FEDERATION_NS);

        writer.writeStartElement("md", "RoleDescriptor", WS_FEDERATION_NS);
        writer.writeAttribute(SCHEMA_INSTANCE_NS, "type", "fed:SecurityTokenServiceType");
        writer.writeAttribute("protocolSupportEnumeration", WS_FEDERATION_NS);
        if (config.getServiceDescription() != null && config.getServiceDescription().length() > 0) {
            writer.writeAttribute("ServiceDescription", config.getServiceDescription());
        }
        if (config.getServiceDisplayName() != null && config.getServiceDisplayName().length() > 0) {
            writer.writeAttribute("ServiceDisplayName", config.getServiceDisplayName());
        }

        //http://docs.oasis-open.org/security/saml/v2.0/saml-schema-metadata-2.0.xsd
        //missing organization, contactperson

        //KeyDescriptor
        writer.writeStartElement("md", "KeyDescriptor", SAML2_METADATA_NS);
        writer.writeAttribute("use", "signing");
        writer.writeStartElement("ds", "KeyInfo", "http://www.w3.org/2000/09/xmldsig#");
        writer.writeNamespace("ds", "http://www.w3.org/2000/09/xmldsig#");
        writer.writeStartElement("ds", "X509Data", "http://www.w3.org/2000/09/xmldsig#");
        writer.writeStartElement("ds", "X509Certificate", "http://www.w3.org/2000/09/xmldsig#");

        try {
            String keyAlias = crypto.getDefaultX509Identifier();
            X509Certificate cert = CertsUtils.getX509CertificateFromCrypto(crypto, keyAlias);
            writer.writeCharacters(Base64.getEncoder().encodeToString(cert.getEncoded()));
        } catch (Exception ex) {
            LOG.error("Failed to add certificate information to metadata. Metadata incomplete", ex);
        }

        writer.writeEndElement(); // X509Certificate
        writer.writeEndElement(); // X509Data
        writer.writeEndElement(); // KeyInfo
        writer.writeEndElement(); // KeyDescriptor


        // SecurityTokenServiceEndpoint
        writer.writeStartElement("fed", "SecurityTokenServiceEndpoint", WS_FEDERATION_NS);
        writer.writeStartElement("wsa", "EndpointReference", WS_ADDRESSING_NS);

        writer.writeStartElement("wsa", "Address", WS_ADDRESSING_NS);
        writer.writeCharacters(config.getStsUrl().toString());

        writer.writeEndElement(); // Address
        writer.writeEndElement(); // EndpointReference
        writer.writeEndElement(); // SecurityTokenServiceEndpoint


        // PassiveRequestorEndpoint
        writer.writeStartElement("fed", "PassiveRequestorEndpoint", WS_FEDERATION_NS);
        writer.writeStartElement("wsa", "EndpointReference", WS_ADDRESSING_NS);

        writer.writeStartElement("wsa", "Address", WS_ADDRESSING_NS);
        writer.writeCharacters(config.getIdpUrl().toString());

        writer.writeEndElement(); // Address
        writer.writeEndElement(); // EndpointReference
        writer.writeEndElement(); // PassiveRequestorEndpoint


        // create ClaimsType section
        if (config.getClaimTypesOffered() != null && !config.getClaimTypesOffered().isEmpty()) {
            writer.writeStartElement("fed", "ClaimTypesOffered", WS_FEDERATION_NS);
            for (Claim claim : config.getClaimTypesOffered()) {

                writer.writeStartElement("auth", "ClaimType", WS_FEDERATION_NS);
                writer.writeAttribute("Uri", claim.getClaimType().toString());
                writer.writeAttribute("Optional", "true");
                writer.writeEndElement(); // ClaimType

            }
            writer.writeEndElement(); // ClaimTypesOffered
        }

        writer.writeEndElement(); // RoleDescriptor
    }

    private void writeSAMLSSOMetadata(
        XMLStreamWriter writer, Idp config, Crypto crypto
    ) throws XMLStreamException {

        writer.writeStartElement("md", "IDPSSODescriptor", SAML2_METADATA_NS);
        writer.writeAttribute("WantAuthnRequestsSigned", "true");
        writer.writeAttribute("protocolSupportEnumeration", "urn:oasis:names:tc:SAML:2.0:protocol");

        //KeyDescriptor
        writer.writeStartElement("md", "KeyDescriptor", SAML2_METADATA_NS);
        writer.writeAttribute("use", "signing");
        writer.writeStartElement("ds", "KeyInfo", "http://www.w3.org/2000/09/xmldsig#");
        writer.writeNamespace("ds", "http://www.w3.org/2000/09/xmldsig#");
        writer.writeStartElement("ds", "X509Data", "http://www.w3.org/2000/09/xmldsig#");
        writer.writeStartElement("ds", "X509Certificate", "http://www.w3.org/2000/09/xmldsig#");

        try {
            String keyAlias = crypto.getDefaultX509Identifier();
            X509Certificate cert = CertsUtils.getX509CertificateFromCrypto(crypto, keyAlias);
            writer.writeCharacters(Base64.getEncoder().encodeToString(cert.getEncoded()));
        } catch (Exception ex) {
            LOG.error("Failed to add certificate information to metadata. Metadata incomplete", ex);
        }

        writer.writeEndElement(); // X509Certificate
        writer.writeEndElement(); // X509Data
        writer.writeEndElement(); // KeyInfo
        writer.writeEndElement(); // KeyDescriptor


        writer.writeStartElement("md", "NameIDFormat", SAML2_METADATA_NS);
        writer.writeCharacters("urn:oasis:names:tc:SAML:2.0:nameid-format:persistent");
        writer.writeEndElement(); // NameIDFormat

        writer.writeStartElement("md", "NameIDFormat", SAML2_METADATA_NS);
        writer.writeCharacters("urn:oasis:names:tc:SAML:2.0:nameid-format:unspecified");
        writer.writeEndElement(); // NameIDFormat

        writer.writeStartElement("md", "NameIDFormat", SAML2_METADATA_NS);
        writer.writeCharacters("urn:oasis:names:tc:SAML:2.0:nameid-format:emailAddress");
        writer.writeEndElement(); // NameIDFormat

        // SingleSignOnService
        writer.writeStartElement("md", "SingleSignOnService", SAML2_METADATA_NS);
        writer.writeAttribute("Binding", "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect");
        writer.writeAttribute("Location", config.getIdpUrl().toString());
        writer.writeEndElement(); // SingleSignOnService

        // SingleSignOnService
        writer.writeStartElement("md", "SingleSignOnService", SAML2_METADATA_NS);
        writer.writeAttribute("Binding", "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST");
        writer.writeAttribute("Location", config.getIdpUrl().toString());
        writer.writeEndElement(); // SingleSignOnService

        writer.writeEndElement(); // IDPSSODescriptor
    }

}
