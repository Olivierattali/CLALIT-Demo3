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
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
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
 * Converts a HIST_PAT message (MSH, PID, PRB*, AL1*) using the custom message structure and the
 * HIST_PAT.yml template from additional_resources, and verifies the exact set of FHIR resources.
 */
public class HistPatMessageTest {

    private static final FHIRContext context = new FHIRContext();
    // Validation is off because the custom HIST_* templates produce non-standard resources.
    private static final ConverterOptions OPTIONS = new Builder().withPrettyPrint().build();
    private static final String CONF_PROP_HOME = "hl7converter.config.home";

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
    public void testHistPatAllSegmentsProduceExpectedResources() {
        String hl7message = "MSH|^~\\&|WHI BULK|WHI|WHI||20211005105125||HIST^PAT|1a3952f1-38fe-4d55-95c6-ce58ebfc7f10|P|2.6\n"
                + "PID|1|100009^^^FAC^MR|100009^^^FAC^MR||Doo^Scooby||195001010000|M|||311 N Keene Street^^COLUMBIA^MO^65201^ US||5734421788|||U\n"
                + "PRB|1|20211005|10281^LYMPHOID LEUKEMIA NEC^ICD9||||201208061011||201208061011|||||||201208061011\n"
                + "PRB|2|20211005|11334^ABNORMALITIES OF HAIR^ICD9||||201208071000||201208071000|||||||201208071000\n"
                + "AL1|1|DA|PENIC^penicillin^L|MO||20210629\n"
                + "AL1|2|MA|CATD^cat dander^L|SV|hives|20210629\n";

        HL7ToFHIRConverter ftv = new HL7ToFHIRConverter();
        String json = ftv.convert(hl7message, OPTIONS);
        assertThat(json).isNotBlank();

        IBaseResource bundleResource = context.getParser().parseResource(json);
        assertThat(bundleResource).isNotNull();
        Bundle b = (Bundle) bundleResource;
        List<BundleEntryComponent> e = b.getEntry();

        // HIST_PAT.yml maps MessageHeader from MSH, but MessageHeader.eventCoding is required and
        // the custom trigger event "PAT" is not a valid HL7 table 0003 code, so the resource is never emitted.
        List<Resource> messageHeaderResource = ResourceUtils.getResourceList(e, ResourceType.MessageHeader);
        assertThat(messageHeaderResource).isEmpty();

        List<Resource> patientResource = ResourceUtils.getResourceList(e, ResourceType.Patient);
        assertThat(patientResource).hasSize(1);
        Patient patient = (Patient) patientResource.get(0);
        assertThat(patient.getIdentifier()).extracting(i -> i.getValue()).contains("100009");
        assertThat(patient.getNameFirstRep().getFamily()).isEqualTo("Doo");
        assertThat(patient.getNameFirstRep().getGivenAsSingleString()).isEqualTo("Scooby");

        List<Resource> conditionResource = ResourceUtils.getResourceList(e, ResourceType.Condition);
        assertThat(conditionResource).hasSize(2);
        List<String> conditionCodes = conditionResource.stream()
                .map(r -> ((Condition) r).getCode().getCodingFirstRep().getCode())
                .collect(Collectors.toList());
        assertThat(conditionCodes).containsExactlyInAnyOrder("10281", "11334");
        for (Resource r : conditionResource) {
            assertThat(((Condition) r).getSubject().getReference()).isEqualTo("Patient/" + patient.getIdElement().getIdPart());
        }

        List<Resource> allergyResource = ResourceUtils.getResourceList(e, ResourceType.AllergyIntolerance);
        assertThat(allergyResource).hasSize(2);
        List<String> allergyCodes = allergyResource.stream()
                .map(r -> ((AllergyIntolerance) r).getCode().getCodingFirstRep().getCode())
                .collect(Collectors.toList());
        assertThat(allergyCodes).containsExactlyInAnyOrder("PENIC", "CATD");
        for (Resource r : allergyResource) {
            assertThat(((AllergyIntolerance) r).getPatient().getReference()).isEqualTo("Patient/" + patient.getIdElement().getIdPart());
        }

        // Exact total: 1 Patient + 2 Condition + 2 AllergyIntolerance
        assertThat(e).hasSize(5);
    }
}
