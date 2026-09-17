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
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
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
 * End-to-end conversion test for the custom HIST_RPT message structure (MSH, PID, PV1, OBR, OBX*)
 * mapped by src/test/resources/additional_resources/hl7/message/HIST_RPT.yml.
 */
public class HistRptMessageTest {

    private static final String CONF_PROP_HOME = "hl7converter.config.home";
    // Validation is intentionally off: HIST_* templates produce non-standard resources.
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
    public void testHistRptAllSegmentsProduceExpectedResources() {
        String hl7message = "MSH|^~\\&|WHI BULK|WHI|WHI||20210709162149||HIST^RPT|20210709162149|P|2.6|||ER|AL\n"
                + "PID|1||100008^^^FAC^MR||Doe^Clinton||197005150000|M|||KEENE ST^^COLUMBIA^MO^65201^ US\n"
                + "PV1||O|||||||||||||||||3.2191\n"
                + "OBR|1||1206141230440|X71010^Xray chest 1 vw^INTERNAL|||201206141232||||||||||||||||||F|||||||123456789^TEST^INTERP^^^|||987654321^TEST^TRANSCRIPT^^^\n"
                + "OBX|1|ST|IMP^Impression^INTERNAL||No acute cardiopulmonary process.||||||F\n"
                + "OBX|2|NM|8867-4^Heart rate^LN||72|/min|||||F\n";

        HL7ToFHIRConverter ftv = new HL7ToFHIRConverter();
        String json = ftv.convert(hl7message, OPTIONS);
        assertThat(json).isNotBlank();
        IBaseResource bundleResource = context.getParser().parseResource(json);
        assertThat(bundleResource).isNotNull();
        Bundle b = (Bundle) bundleResource;
        List<BundleEntryComponent> e = b.getEntry();

        // Exact count of every expected resource type
        List<Resource> patientResource = ResourceUtils.getResourceList(e, ResourceType.Patient);
        assertThat(patientResource).hasSize(1);

        List<Resource> encounterResource = ResourceUtils.getResourceList(e, ResourceType.Encounter);
        assertThat(encounterResource).hasSize(1);

        List<Resource> observationResource = ResourceUtils.getResourceList(e, ResourceType.Observation);
        assertThat(observationResource).hasSize(2);

        List<Resource> diagnosticReportResource = ResourceUtils.getResourceList(e, ResourceType.DiagnosticReport);
        assertThat(diagnosticReportResource).hasSize(1);

        List<Resource> serviceRequestResource = ResourceUtils.getResourceList(e, ResourceType.ServiceRequest);
        assertThat(serviceRequestResource).hasSize(1);

        List<Resource> practitionerResource = ResourceUtils.getResourceList(e, ResourceType.Practitioner);
        assertThat(practitionerResource).hasSize(1);

        // Confirm that no extra resources are created
        assertThat(e.size()).isEqualTo(7);

        // Patient content from PID.3 and PID.5
        Patient patient = ResourceUtils.getResourcePatient(patientResource.get(0), context);
        assertThat(patient.getIdentifierFirstRep().getValue()).isEqualTo("100008");
        assertThat(patient.getNameFirstRep().getFamily()).isEqualTo("Doe");
        assertThat(patient.getNameFirstRep().getGivenAsSingleString()).isEqualTo("Clinton");

        // Encounter content from PV1.19 and PV1.2
        Encounter encounter = ResourceUtils.getResourceEncounter(encounterResource.get(0), context);
        assertThat(encounter.getIdentifierFirstRep().getValue()).isEqualTo("3.2191");
        assertThat(encounter.getClass_().getCode()).isEqualTo("AMB");
        assertThat(encounter.getSubject().getReference()).isEqualTo("Patient/" + patient.getIdElement().getIdPart());

        // Observation content from OBX.3 and OBX.5 of each repetition
        List<Observation> observations = observationResource.stream()
                .map(r -> ResourceUtils.getResourceObservation(r, context))
                .sorted(Comparator.comparing(o -> o.getCode().getCodingFirstRep().getCode()))
                .collect(Collectors.toList());
        Observation obs1 = observations.get(1); // IMP sorts after 8867-4
        Observation obs2 = observations.get(0);
        assertThat(obs1.getCode().getCodingFirstRep().getCode()).isEqualTo("IMP");
        assertThat(obs1.getValueStringType().getValue()).isEqualTo("No acute cardiopulmonary process.");
        assertThat(obs1.getSubject().getReference()).isEqualTo("Patient/" + patient.getIdElement().getIdPart());

        assertThat(obs2.getCode().getCodingFirstRep().getCode()).isEqualTo("8867-4");
        assertThat(obs2.getCode().getCodingFirstRep().getSystem()).isEqualTo("http://loinc.org");
        assertThat(obs2.getValueQuantity().getValue().intValue()).isEqualTo(72);
        assertThat(obs2.getValueQuantity().getUnit()).isEqualTo("/min");

        // DiagnosticReport content from OBR.4, OBR.25, OBR.3 and links to Observations/ServiceRequest
        DiagnosticReport diag = ResourceUtils.getResourceDiagnosticReport(diagnosticReportResource.get(0), context);
        Coding diagCode = diag.getCode().getCodingFirstRep();
        assertThat(diagCode.getCode()).isEqualTo("X71010");
        assertThat(diagCode.getDisplay()).isEqualTo("Xray chest 1 vw");
        assertThat(diag.getStatus().toCode()).isEqualTo("final");
        assertThat(diag.getIdentifier()).anyMatch(id -> "1206141230440".equals(id.getValue()));
        assertThat(diag.getResult()).hasSize(2);
        assertThat(diag.getResult()).extracting(r -> r.getReference())
                .containsExactlyInAnyOrder("Observation/" + obs1.getIdElement().getIdPart(), "Observation/" + obs2.getIdElement().getIdPart());
        assertThat(diag.getBasedOn()).hasSize(1);
        assertThat(diag.getResultsInterpreter()).hasSize(1);

        // ServiceRequest content from OBR.4 / OBR.3
        ServiceRequest servReq = ResourceUtils.getResourceServiceRequest(serviceRequestResource.get(0), context);
        assertThat(servReq.getCode().getCodingFirstRep().getCode()).isEqualTo("X71010");
        assertThat(servReq.getIdentifier()).anyMatch(id -> "1206141230440".equals(id.getValue()));
        assertThat(diag.getBasedOnFirstRep().getReference()).isEqualTo("ServiceRequest/" + servReq.getIdElement().getIdPart());

        // Practitioner content from OBR.32 (results interpreter); OBR.35 transcriptionist is not mapped
        Practitioner practitioner = ResourceUtils.getResourcePractitioner(practitionerResource.get(0), context);
        assertThat(practitioner.getIdentifierFirstRep().getValue()).isEqualTo("123456789");
        assertThat(diag.getResultsInterpreterFirstRep().getReference())
                .isEqualTo("Practitioner/" + practitioner.getIdElement().getIdPart());
    }
}
