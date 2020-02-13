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

package org.apache.cxf.spring.boot.autoconfigure.micrometer;

import org.apache.cxf.metrics.MetricsFeature;
import org.apache.cxf.metrics.MetricsProvider;
import org.apache.cxf.metrics.micrometer.MicrometerMetricsProperties;
import org.apache.cxf.metrics.micrometer.MicrometerMetricsProvider;
import org.apache.cxf.metrics.micrometer.provider.TagsProvider;
import org.apache.cxf.metrics.micrometer.provider.TimedAnnotationProvider;
import org.apache.cxf.metrics.micrometer.provider.DefaultExceptionClassProvider;
import org.apache.cxf.metrics.micrometer.provider.ExceptionClassProvider;
import org.apache.cxf.metrics.micrometer.provider.jaxws.DefaultJaxwsFaultCodeProvider;
import org.apache.cxf.metrics.micrometer.provider.jaxws.JaxwsFaultCodeProvider;
import org.apache.cxf.metrics.micrometer.provider.jaxws.JaxwsTags;
import org.apache.cxf.metrics.micrometer.provider.jaxws.JaxwsTagsProvider;
import org.apache.cxf.spring.boot.autoconfigure.MetricsProperties;
import org.apache.cxf.spring.boot.autoconfigure.micrometer.provider.jaxws.SpringBasedTimedAnnotationProvider;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.OnlyOnceLoggingDenyMeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.DispatcherServlet;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;

@Configuration
@AutoConfigureAfter({MetricsAutoConfiguration.class, SimpleMetricsExportAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({DispatcherServlet.class, MetricsFeature.class})
@ConditionalOnBean({MeterRegistry.class})
@EnableConfigurationProperties(MetricsProperties.class)
public class MicrometerMetricsAutoConfiguration {

    private final MetricsProperties properties;

    public MicrometerMetricsAutoConfiguration(MetricsProperties properties) {
        this.properties = properties;
    }

    @Bean
    public TimedAnnotationProvider timedAnnotationProvider() {
        return new SpringBasedTimedAnnotationProvider();
    }

    @Bean
    public JaxwsTags jaxwsTags() {
        return new JaxwsTags();
    }

    @Bean
    @ConditionalOnMissingBean(ExceptionClassProvider.class)
    public ExceptionClassProvider exceptionClassProvider() {
        return new DefaultExceptionClassProvider();
    }

    @Bean
    @ConditionalOnMissingBean(JaxwsFaultCodeProvider.class)
    public JaxwsFaultCodeProvider jaxwsFaultCodeProvider() {
        return new DefaultJaxwsFaultCodeProvider();
    }

    @Bean
    @ConditionalOnMissingBean(TagsProvider.class)
    public TagsProvider tagsProvider(ExceptionClassProvider exceptionClassProvider,
                                     JaxwsFaultCodeProvider faultCodeProvider,
                                     JaxwsTags cxfTags) {
        return new JaxwsTagsProvider(exceptionClassProvider, faultCodeProvider, cxfTags);
    }

    @Bean
    @ConditionalOnMissingBean(MetricsProvider.class)
    public MetricsProvider metricsProvider(TagsProvider tagsProvider,
                                           TimedAnnotationProvider timedAnnotationProvider,
                                           MeterRegistry registry) {
        MicrometerMetricsProperties micrometerMetricsProperties = new MicrometerMetricsProperties();

        MetricsProperties.Soap.Server server = this.properties.getSoap().getServer();
        micrometerMetricsProperties.setAutoTimeRequests(server.isAutoTimeRequests());
        micrometerMetricsProperties.setRequestsMetricName(server.getRequestsMetricName());

        return new MicrometerMetricsProvider(registry, tagsProvider, timedAnnotationProvider,
                micrometerMetricsProperties);
    }

    @Bean
    @Order(0)
    public MeterFilter metricsSoapServerUriTagFilter() {
        String metricName = this.properties.getSoap().getServer().getRequestsMetricName();
        MeterFilter filter = new OnlyOnceLoggingDenyMeterFilter(
        () -> String.format("Reached the maximum number of URI tags for '%s'.", metricName));
        return MeterFilter.maximumAllowableTags(
                metricName, "uri", this.properties.getSoap().getServer().getMaxUriTags(), filter);
    }
}
