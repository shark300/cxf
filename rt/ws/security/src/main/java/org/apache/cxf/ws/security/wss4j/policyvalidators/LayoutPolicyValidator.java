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

package org.apache.cxf.ws.security.wss4j.policyvalidators;

import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import javax.xml.namespace.QName;

import org.w3c.dom.Element;
import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.helpers.CastUtils;
import org.apache.cxf.message.Message;
import org.apache.cxf.ws.policy.AssertionInfo;
import org.apache.cxf.ws.policy.AssertionInfoMap;
import org.apache.cxf.ws.security.policy.PolicyUtils;
import org.apache.wss4j.common.saml.SAMLKeyInfo;
import org.apache.wss4j.common.saml.SamlAssertionWrapper;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.WSDataRef;
import org.apache.wss4j.dom.WSSecurityEngine;
import org.apache.wss4j.dom.WSSecurityEngineResult;
import org.apache.wss4j.dom.message.token.BinarySecurity;
import org.apache.wss4j.dom.message.token.PKIPathSecurity;
import org.apache.wss4j.dom.message.token.X509Security;
import org.apache.wss4j.policy.SPConstants;
import org.apache.wss4j.policy.model.Layout;
import org.apache.wss4j.policy.model.Layout.LayoutType;

/**
 * Validate a Layout policy.
 */
public class LayoutPolicyValidator extends AbstractTokenPolicyValidator {
    
    public boolean validatePolicy(
        AssertionInfoMap aim,
        Message message,
        Element soapBody,
        List<WSSecurityEngineResult> results,
        List<WSSecurityEngineResult> signedResults
    ) {
        Collection<AssertionInfo> ais = 
            PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.LAYOUT);
        if (!ais.isEmpty()) {
            parsePolicies(aim, ais, message, results, signedResults);
        }

        return true;
    }
        
    private void parsePolicies(
        AssertionInfoMap aim,
        Collection<AssertionInfo> ais, 
        Message message,  
        List<WSSecurityEngineResult> results,
        List<WSSecurityEngineResult> signedResults
    ) {
        for (AssertionInfo ai : ais) {
            Layout layout = (Layout)ai.getAssertion();
            ai.setAsserted(true);
            assertToken(layout, aim);
            
            if (!validatePolicy(layout, results, signedResults)) {
                String error = "Layout does not match the requirements";
                ai.setNotAsserted(error);
            }
        }
    }
    
    private void assertToken(Layout token, AssertionInfoMap aim) {
        String namespace = token.getName().getNamespaceURI();

        LayoutType layoutType = token.getLayoutType();
        if (layoutType != null) {
            PolicyUtils.assertPolicy(aim, new QName(namespace, layoutType.name()));
        }
    }
    
    public boolean validatePolicy(
        Layout layout, 
        List<WSSecurityEngineResult> results,
        List<WSSecurityEngineResult> signedResults
    ) {
        boolean timestampFirst = layout.getLayoutType() == LayoutType.LaxTsFirst;
        boolean timestampLast = layout.getLayoutType() == LayoutType.LaxTsLast;
        boolean strict = layout.getLayoutType() == LayoutType.Strict;
        
        if (timestampFirst) {
            if (results.isEmpty()) {
                return false;
            }
            Integer firstAction = (Integer)results.get(results.size() - 1).get(WSSecurityEngineResult.TAG_ACTION);
            if (firstAction.intValue() != WSConstants.TS) {
                return false;
            }
        } else if (timestampLast) {
            if (results.isEmpty()) {
                return false;
            }
            Integer lastAction = 
                (Integer)results.get(0).get(WSSecurityEngineResult.TAG_ACTION);
            if (lastAction.intValue() != WSConstants.TS) {
                return false;
            }
        } else if (strict && (!validateStrictSignaturePlacement(results, signedResults) 
            || !validateStrictSignatureTokenPlacement(results)
            || !checkSignatureIsSignedPlacement(signedResults))) {
            return false;
        }
        
        return true;
    }
    
    private boolean validateStrictSignaturePlacement(
        List<WSSecurityEngineResult> results,
        List<WSSecurityEngineResult> signedResults
    ) {
        // Go through each Signature and check any security header token is before the Signature
        for (WSSecurityEngineResult signedResult : signedResults) {
            List<WSDataRef> sl = 
                CastUtils.cast((List<?>)signedResult.get(WSSecurityEngineResult.TAG_DATA_REF_URIS));
            Integer actInt = (Integer)signedResult.get(WSSecurityEngineResult.TAG_ACTION);
            if (sl == null || WSConstants.ST_SIGNED == actInt) {
                continue;
            }

            for (WSDataRef r : sl) {
                String xpath = r.getXpath();
                if (xpath != null) {
                    String[] nodes = StringUtils.split(xpath, "/");
                    // envelope/Header/wsse:Security/header
                    if (nodes.length == 5) {
                        Element protectedElement = r.getProtectedElement();
                        boolean tokenFound = false;
                        // Results are stored in reverse order
                        for (WSSecurityEngineResult result : results) {
                            Element resultElement = 
                                (Element)result.get(WSSecurityEngineResult.TAG_TOKEN_ELEMENT);
                            if (resultElement == protectedElement) {
                                tokenFound = true;
                            }
                            if (tokenFound && result == signedResult) {
                                return false;
                            } else if (resultElement != null && result == signedResult) {
                                break;
                            }
                        }
                    }
                }
            }
        }

        return true;
    }
    
    private boolean validateStrictSignatureTokenPlacement(List<WSSecurityEngineResult> results) {
        // Go through each Signature and check that the Signing Token appears before the Signature
        for (int i = 0; i < results.size(); i++) {
            WSSecurityEngineResult result = results.get(i);
            Integer actInt = (Integer)result.get(WSSecurityEngineResult.TAG_ACTION);
            if (actInt == WSConstants.SIGN) {
                int correspondingIndex = findCorrespondingTokenIndex(result, results);
                if (correspondingIndex > 0 && correspondingIndex < i) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    private boolean checkSignatureIsSignedPlacement(List<WSSecurityEngineResult> signedResults) {
        for (int i = 0; i < signedResults.size(); i++) {
            WSSecurityEngineResult signedResult = signedResults.get(i);
            List<WSDataRef> sl =
                CastUtils.cast((List<?>)signedResult.get(
                    WSSecurityEngineResult.TAG_DATA_REF_URIS
                ));
            if (sl != null && sl.size() >= 1) {
                for (WSDataRef dataRef : sl) {
                    QName signedQName = dataRef.getName();
                    if (WSSecurityEngine.SIGNATURE.equals(signedQName)) {
                        Element protectedElement = dataRef.getProtectedElement();
                        boolean endorsingSigFound = false;
                        // Results are stored in reverse order
                        for (WSSecurityEngineResult result : signedResults) {
                            if (result == signedResult) {
                                endorsingSigFound = true;
                            }
                            Element resultElement = 
                                (Element)result.get(WSSecurityEngineResult.TAG_TOKEN_ELEMENT);
                            if (resultElement == protectedElement) {
                                if (endorsingSigFound) {
                                    break;
                                } else {
                                    return false;
                                }
                            }
                        }
                    }
                }
            }
        }
        return true;
    }
    
    /**
     * Find the index of the token corresponding to either the X509Certificate or PublicKey used 
     * to sign the "signatureResult" argument.
     */
    private int findCorrespondingTokenIndex(
        WSSecurityEngineResult signatureResult,
        List<WSSecurityEngineResult> results
    ) {
        // See what was used to sign this result
        X509Certificate cert = 
            (X509Certificate)signatureResult.get(WSSecurityEngineResult.TAG_X509_CERTIFICATE);
        PublicKey publicKey = 
            (PublicKey)signatureResult.get(WSSecurityEngineResult.TAG_PUBLIC_KEY);
        
        for (int i = 0; i < results.size(); i++) {
            WSSecurityEngineResult token = results.get(i);
            Integer actInt = (Integer)token.get(WSSecurityEngineResult.TAG_ACTION);
            if (actInt == WSConstants.SIGN) {
                continue;
            }
            
            BinarySecurity binarySecurity = 
                (BinarySecurity)token.get(WSSecurityEngineResult.TAG_BINARY_SECURITY_TOKEN);
            PublicKey foundPublicKey = 
                (PublicKey)token.get(WSSecurityEngineResult.TAG_PUBLIC_KEY);
            if (binarySecurity instanceof X509Security
                || binarySecurity instanceof PKIPathSecurity) {
                X509Certificate foundCert = 
                    (X509Certificate)token.get(WSSecurityEngineResult.TAG_X509_CERTIFICATE);
                if (foundCert.equals(cert)) {
                    return i;
                }
            } else if (actInt.intValue() == WSConstants.ST_SIGNED
                || actInt.intValue() == WSConstants.ST_UNSIGNED) {
                SamlAssertionWrapper assertionWrapper = 
                    (SamlAssertionWrapper)token.get(WSSecurityEngineResult.TAG_SAML_ASSERTION);
                SAMLKeyInfo samlKeyInfo = assertionWrapper.getSubjectKeyInfo();
                if (samlKeyInfo != null) {
                    X509Certificate[] subjectCerts = samlKeyInfo.getCerts();
                    PublicKey subjectPublicKey = samlKeyInfo.getPublicKey();
                    if ((cert != null && subjectCerts != null 
                        && cert.equals(subjectCerts[0]))
                        || (subjectPublicKey != null && subjectPublicKey.equals(publicKey))) {
                        return i;
                    }
                }
            } else if (publicKey != null && publicKey.equals(foundPublicKey)) {
                return i;
            } 
        }
        return -1;
    }
}
