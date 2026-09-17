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
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
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
import io.github.linuxforhealth.hl7.segments.util.DatatypeUtils;
import io.github.linuxforhealth.hl7.segments.util.ResourceUtils;

/**
 * End-to-end conversion test for the custom HIST_LAB message structure
 * (MSH, PID, PV1, OBR, OBX*, SPM) against the HIST_LAB.yml template.
 */
public class HistLabMessageTest {

    // Validation is intentionally off: the custom HIST_* structures are not standard HL7.
    private static final ConverterOptions OPTIONS = new Builder().withPrettyPrint().build();
    private static final String CONF_PROP_HOME = "hl7converter.config.home";
    private static final FHIRContext context = new FHIRContext();

    @TempDir
    static File folder;

    static String originalConfigHome;

    @BeforeAll
    public static void saveConfigHomeProperty() {
        originalConfigHome = System.getProperty(CONF_PROP_HOME);
    }

    @BeforeEach
    public void setupConfig() throws IOException {
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
    public void testHistLabAllSegments() throws IOException {
        String hl7message = "MSH|^~\\&|WHI BULK|WHI|WHI||20211005172734||HIST^LAB|4bb9d61c-337d-441c-bfd6-015b9721cdc8|P|2.6\n"
                + "PID|1|100014^^^FAC^MR|||Sullivan^April||198302090000|F|||123 Main St^^COLUMBIA^MO^65201^US\n"
                + "PV1||I|||||||||||||||||VISIT9341|||||||||||||||||||||||||20211005120000\n"
                + "OBR|1|ORD123^PLACER|FIL456^FILLER|LAB^LAB RESULT^L|||20120822160000|||||||||||||||||F\n"
                + "OBX|1|NM|Glu^Glucose Level^LABCORP|1|90.53|mg/dL|70-100|N|||F|||201208221628\n"
                + "OBX|2|NM|Glu^Glucose Level^LABCORP|2|90.47|mg/dL|70-100|N|||F|||201208221629\n"
                + "SPM|1|SPEC001||BLD^Blood^HL70487||||||||||||20120822155500\n";

        HL7ToFHIRConverter ftv = new HL7ToFHIRConverter();
        String json = ftv.convert(hl7message, OPTIONS);
        assertThat(json).isNotBlank();
        IBaseResource bundleResource = context.getParser().parseResource(json);
        assertThat(bundleResource).isNotNull();
        Bundle b = (Bundle) bundleResource;
        List<BundleEntryComponent> e = b.getEntry();

        // Patient from PID
        List<Resource> patientResource = ResourceUtils.getResourceList(e, ResourceType.Patient);
        assertThat(patientResource).hasSize(1);
        Patient patient = (Patient) patientResource.get(0);
        assertThat(patient.getNameFirstRep().getFamily()).isEqualTo("Sullivan");
        assertThat(patient.getNameFirstRep().getGivenAsSingleString()).isEqualTo("April");
        assertThat(patient.getGender().toCode()).isEqualTo("female");
        assertThat(patient.getBirthDateElement().getValueAsString()).isEqualTo("1983-02-09");
        assertThat(patient.getAddressFirstRep().getCity()).isEqualTo("COLUMBIA");
        String patientRef = "Patient/" + patient.getIdElement().getIdPart();

        // Encounter from PV1
        List<Resource> encounterResource = ResourceUtils.getResourceList(e, ResourceType.Encounter);
        assertThat(encounterResource).hasSize(1);
        Encounter encounter = (Encounter) encounterResource.get(0);
        assertThat(encounter.getIdentifierFirstRep().getValue()).isEqualTo("VISIT9341");
        assertThat(encounter.getClass_().getCode()).isEqualTo("IMP");
        assertThat(encounter.getSubject().getReference()).isEqualTo(patientRef);

        // One Observation per OBX, laboratory category because SPM is present
        List<Resource> observationResource = ResourceUtils.getResourceList(e, ResourceType.Observation);
        assertThat(observationResource).hasSize(2);
        for (Resource r : observationResource) {
            Observation obs = (Observation) r;
            assertThat(obs.getCode().getCodingFirstRep().getCode()).isEqualTo("Glu");
            assertThat(obs.getCode().getCodingFirstRep().getDisplay()).isEqualTo("Glucose Level");
            assertThat(obs.getValue()).isInstanceOf(Quantity.class);
            assertThat(((Quantity) obs.getValue()).getUnit()).isEqualTo("mg/dL");
            assertThat(obs.getSubject().getReference()).isEqualTo(patientRef);
            assertThat(obs.getCategory()).hasSize(1);
            DatatypeUtils.checkCommonCodeableConceptAssertions(obs.getCategoryFirstRep(), "laboratory", "Laboratory",
                    "http://terminology.hl7.org/CodeSystem/observation-category", null);
        }
        List<String> obsValues = observationResource.stream()
                .map(r -> ((Quantity) ((Observation) r).getValue()).getValue().toPlainString())
                .collect(Collectors.toList());
        assertThat(obsValues).containsExactlyInAnyOrder("90.53", "90.47");

        // DiagnosticReport from OBR, referencing both observations
        List<Resource> diagnosticReportResource = ResourceUtils.getResourceList(e, ResourceType.DiagnosticReport);
        assertThat(diagnosticReportResource).hasSize(1);
        DiagnosticReport report = (DiagnosticReport) diagnosticReportResource.get(0);
        assertThat(report.getCode().getCodingFirstRep().getCode()).isEqualTo("LAB");
        assertThat(report.getCode().getCodingFirstRep().getDisplay()).isEqualTo("LAB RESULT");
        assertThat(report.getResult()).hasSize(2);
        assertThat(report.getSubject().getReference()).isEqualTo(patientRef);
        assertThat(report.getIdentifier().stream().map(Identifier::getValue)).contains("ORD123", "FIL456");

        // ServiceRequest created from OBR by the DiagnosticReport template
        List<Resource> serviceRequestResource = ResourceUtils.getResourceList(e, ResourceType.ServiceRequest);
        assertThat(serviceRequestResource).hasSize(1);
        ServiceRequest serviceRequest = (ServiceRequest) serviceRequestResource.get(0);
        assertThat(serviceRequest.getCode().getCodingFirstRep().getCode()).isEqualTo("LAB");
        assertThat(report.getBasedOnFirstRep().getReference())
                .isEqualTo("ServiceRequest/" + serviceRequest.getIdElement().getIdPart());

        // Confirm nothing was silently dropped or added
        assertThat(e).hasSize(6);
    }
}
