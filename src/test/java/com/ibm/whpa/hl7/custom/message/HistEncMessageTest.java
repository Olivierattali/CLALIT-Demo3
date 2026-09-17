/*
 * (C) Copyright IBM Corp. 2021
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.whpa.hl7.custom.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.linuxforhealth.core.config.ConverterConfiguration;
import io.github.linuxforhealth.fhir.FHIRContext;
import io.github.linuxforhealth.hl7.ConverterOptions;
import io.github.linuxforhealth.hl7.ConverterOptions.Builder;
import io.github.linuxforhealth.hl7.HL7ToFHIRConverter;
import io.github.linuxforhealth.hl7.resource.ResourceReader;
import io.github.linuxforhealth.hl7.segments.util.ResourceUtils;

/**
 * Converts a representative HIST_ENC message (custom BJC structure: MSH, PID, OBR*, RXE*, DG1*)
 * and verifies the exact set of FHIR resources produced by HIST_ENC.yml.
 */
public class HistEncMessageTest {

    private static final String CONF_PROP_HOME = "hl7converter.config.home";
    private static final ConverterOptions OPTIONS = new Builder().withPrettyPrint().build();
    private static final FHIRContext context = new FHIRContext();

    @TempDir
    static File folder;

    static String originalConfigHome;

    @BeforeAll
    public static void saveConfigHomeProperty() {
        originalConfigHome = System.getProperty(CONF_PROP_HOME);
    }

    @BeforeEach
    public void setUpConfig() throws IOException {
        File configFile = new File(folder, "config.properties");
        Properties prop = new Properties();
        prop.put("base.path.resource", "src/main/resources");
        prop.put("supported.hl7.messages", "*");
        prop.put("default.zoneid", "+08:00");
        prop.put("additional.resources.location", "src/test/resources/additional_resources");
        try (FileOutputStream out = new FileOutputStream(configFile)) {
            prop.store(out, null);
        }
        System.setProperty(CONF_PROP_HOME, configFile.getParent());
        ConverterConfiguration.reset();
        ResourceReader.reset();
    }

    @AfterEach
    public void reset() {
        System.clearProperty(CONF_PROP_HOME);
        ConverterConfiguration.reset();
        ResourceReader.reset();
    }

    @AfterAll
    public static void reloadPreviousConfigurations() {
        if (originalConfigHome != null)
            System.setProperty(CONF_PROP_HOME, originalConfigHome);
        else
            System.clearProperty(CONF_PROP_HOME);
    }

    @Test
    public void testHistEncAllSegmentsProduceExpectedResources() {
        String hl7message = "MSH|^~\\&|WHI BULK|WHI|WHI||20210709142435||HIST^ENC|20210709142435|P|2.6\n"
                + "PID|1|100000^^^FAC^MR|100000^^^FAC||Vickers^Tony||197910280000|M|||229 S Tyler St^^BEVERLY HILLS^FL^34465^ US\n"
                + "OBR|1|||X73600^XRay Ankle 2 views^INTERNAL|||201206080800|||||||||123456789^TEST^ORDERING^^^||||||||||||||||||89^TEST^TECH^^^\n"
                + "OBR|2|||71550^MRI chest with contrast^INTERNAL|||201206081027|||||||||987654321^SMITH^ORDERING^^^||||||||||||||||||89^TEST^TECH^^^\n"
                + "RXE|1|20^Ibuprofen||||||||100|MG|||||2||||||||||||||||201704010000\n"
                + "RXE|2|10^acetaminophen||||||||200|MG|||||1||||||||||||||||201801010000\n"
                + "DG1|1||704.2^ABNORMALITIES OF HAIR^I9|ABNORMALITIES OF HAIR|202004101359\n"
                + "DG1|2||R03.0^Elevated blood-pressure reading, without diagnosis of hypertension^I10|Elevated blood-pressure reading, without diagnosis of hypertension|202004101405\n";

        HL7ToFHIRConverter ftv = new HL7ToFHIRConverter();
        String json = ftv.convert(hl7message, OPTIONS);
        assertThat(json).isNotBlank();
        IBaseResource bundleResource = context.getParser().parseResource(json);
        assertThat(bundleResource).isNotNull();
        Bundle b = (Bundle) bundleResource;
        List<BundleEntryComponent> e = b.getEntry();

        // Exact resource counts
        List<Resource> patientResource = ResourceUtils.getResourceList(e, ResourceType.Patient);
        assertThat(patientResource).hasSize(1);

        List<Resource> diagnosticReportResource = ResourceUtils.getResourceList(e, ResourceType.DiagnosticReport);
        assertThat(diagnosticReportResource).hasSize(2);

        List<Resource> serviceRequestResource = ResourceUtils.getResourceList(e, ResourceType.ServiceRequest);
        assertThat(serviceRequestResource).hasSize(2);

        List<Resource> practitionerResource = ResourceUtils.getResourceList(e, ResourceType.Practitioner);
        assertThat(practitionerResource).hasSize(2);

        List<Resource> medicationRequestResource = ResourceUtils.getResourceList(e, ResourceType.MedicationRequest);
        assertThat(medicationRequestResource).hasSize(2);

        List<Resource> conditionResource = ResourceUtils.getResourceList(e, ResourceType.Condition);
        assertThat(conditionResource).hasSize(2);

        // MSH is mapped to MessageHeader in HIST_ENC.yml but HIST^ENC is not a known event, so none is created
        assertThat(ResourceUtils.getResourceList(e, ResourceType.MessageHeader)).isEmpty();
        assertThat(ResourceUtils.getResourceList(e, ResourceType.Encounter)).isEmpty();

        // Nothing extra, nothing dropped
        assertThat(e).hasSize(11);

        // Patient content from PID
        Patient patient = ResourceUtils.getResourcePatient(patientResource.get(0), context);
        assertThat(patient.getIdentifier()).extracting(i -> i.getValue()).contains("100000");
        assertThat(patient.getNameFirstRep().getFamily()).isEqualTo("Vickers");
        assertThat(patient.getNameFirstRep().getGivenAsSingleString()).isEqualTo("Tony");
        assertThat(patient.getGender().toCode()).isEqualTo("male");
        String patientRef = "Patient/" + patient.getIdElement().getIdPart();

        // DiagnosticReport content from OBR.4, each referencing the Patient and its own ServiceRequest
        List<DiagnosticReport> reports = diagnosticReportResource.stream()
                .map(r -> ResourceUtils.getResourceDiagnosticReport(r, context)).collect(Collectors.toList());
        assertThat(reports).extracting(r -> r.getCode().getCodingFirstRep().getCode())
                .containsExactlyInAnyOrder("X73600", "71550");
        assertThat(reports).extracting(r -> r.getCode().getCodingFirstRep().getDisplay())
                .containsExactlyInAnyOrder("XRay Ankle 2 views", "MRI chest with contrast");
        for (DiagnosticReport report : reports) {
            assertThat(report.getSubject().getReference()).isEqualTo(patientRef);
            assertThat(report.getBasedOn()).hasSize(1);
            assertThat(report.getBasedOn().get(0).getReference()).startsWith("ServiceRequest/");
        }
        assertThat(reports).extracting(r -> r.getEffectiveDateTimeType().asStringValue())
                .containsExactlyInAnyOrder("2012-06-08T08:00:00+08:00", "2012-06-08T10:27:00+08:00");

        // ServiceRequest content from OBR.4 / OBR.16
        List<ServiceRequest> serviceRequests = serviceRequestResource.stream()
                .map(r -> ResourceUtils.getResourceServiceRequest(r, context)).collect(Collectors.toList());
        assertThat(serviceRequests).extracting(s -> s.getCode().getCodingFirstRep().getCode())
                .containsExactlyInAnyOrder("X73600", "71550");
        for (ServiceRequest sr : serviceRequests) {
            assertThat(sr.getSubject().getReference()).isEqualTo(patientRef);
            assertThat(sr.getRequester().getReference()).startsWith("Practitioner/");
        }

        // Practitioner content from OBR.16 (ordering provider)
        List<Practitioner> practitioners = practitionerResource.stream()
                .map(r -> ResourceUtils.getResourcePractitioner(r, context)).collect(Collectors.toList());
        assertThat(practitioners).extracting(p -> p.getIdentifierFirstRep().getValue())
                .containsExactlyInAnyOrder("123456789", "987654321");
        assertThat(practitioners).extracting(p -> p.getNameFirstRep().getFamily())
                .containsExactlyInAnyOrder("TEST", "SMITH");

        // MedicationRequest content from RXE.2 / RXE.10 / RXE.11
        List<MedicationRequest> medRequests = medicationRequestResource.stream()
                .map(r -> ResourceUtils.getResourceMedicationRequest(r, context)).collect(Collectors.toList());
        assertThat(medRequests).extracting(m -> m.getMedicationCodeableConcept().getCodingFirstRep().getCode())
                .containsExactlyInAnyOrder("20", "10");
        assertThat(medRequests).extracting(m -> m.getMedicationCodeableConcept().getCodingFirstRep().getDisplay())
                .containsExactlyInAnyOrder("Ibuprofen", "acetaminophen");
        for (MedicationRequest mr : medRequests) {
            assertThat(mr.getSubject().getReference()).isEqualTo(patientRef);
        }

        // Condition content from DG1.3
        List<Condition> conditions = conditionResource.stream()
                .map(r -> ResourceUtils.getResourceCondition(r, context)).collect(Collectors.toList());
        assertThat(conditions).extracting(c -> c.getCode().getCodingFirstRep().getCode())
                .containsExactlyInAnyOrder("704.2", "R03.0");
        assertThat(conditions).extracting(c -> c.getCode().getCodingFirstRep().getDisplay())
                .containsExactlyInAnyOrder("ABNORMALITIES OF HAIR",
                        "Elevated blood-pressure reading, without diagnosis of hypertension");
        for (Condition c : conditions) {
            assertThat(c.getSubject().getReference()).isEqualTo(patientRef);
        }
    }
}
