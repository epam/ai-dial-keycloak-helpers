package com.epam.aidial.keycloak.helpers.config;

import lombok.Value;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the D-019 project-entitlement mappers.
 *
 * <p>Backed by mapper model config properties and shared between the IdP mapper
 * (Graph fetch) and the validate-and-emit protocol mapper.
 */
@Value
public class ProjectEntitlementConfiguration {

    String conventionRegex;
    String conventionPrefix;
    String selectionParam;
    String claimName;
    String entitlementAttribute;

    private static final String CONVENTION_REGEX = "convention.regex";
    private static final String CONVENTION_PREFIX = "convention.prefix";
    public static final String SELECTION_PARAM = "selection.param";
    private static final String CLAIM_NAME = "claim.name";
    private static final String ENTITLEMENT_ATTRIBUTE = "entitlement.attribute";

    private static final String DEFAULT_CONVENTION_REGEX = "^Project [A-Za-z0-9]+-[A-Za-z0-9]+$";
    private static final String DEFAULT_CONVENTION_PREFIX = "Project ";
    private static final String DEFAULT_SELECTION_PARAM = "project";
    private static final String DEFAULT_CLAIM_NAME = "project";
    private static final String DEFAULT_ENTITLEMENT_ATTRIBUTE = "projectEntitlement";

    /**
     * Builds configuration from an identity provider mapper model.
     *
     * @param model identity provider mapper model
     * @return configuration
     */
    public static ProjectEntitlementConfiguration fromModel(IdentityProviderMapperModel model) {
        return fromConfig(model.getConfig());
    }

    /**
     * Builds configuration from a protocol mapper model.
     *
     * @param model protocol mapper model
     * @return configuration
     */
    public static ProjectEntitlementConfiguration fromModel(ProtocolMapperModel model) {
        return fromConfig(model.getConfig());
    }

    private static ProjectEntitlementConfiguration fromConfig(java.util.Map<String, String> config) {
        return new ProjectEntitlementConfiguration(
                config.getOrDefault(CONVENTION_REGEX, DEFAULT_CONVENTION_REGEX),
                config.getOrDefault(CONVENTION_PREFIX, DEFAULT_CONVENTION_PREFIX),
                config.getOrDefault(SELECTION_PARAM, DEFAULT_SELECTION_PARAM),
                config.getOrDefault(CLAIM_NAME, DEFAULT_CLAIM_NAME),
                config.getOrDefault(ENTITLEMENT_ATTRIBUTE, DEFAULT_ENTITLEMENT_ATTRIBUTE)
        );
    }

    /**
     * Returns config properties supported by this mapper family.
     *
     * @return list of config properties
     */
    public static List<ProviderConfigProperty> getConfigProperties() {
        List<ProviderConfigProperty> properties = new ArrayList<>();
        properties.add(textProperty(CONVENTION_REGEX, "Project Group Name Regex",
                "Regular expression a project group's display name must match to ever yield an entitlement value "
                        + "(D-019 fail-loud on unrecognized names)", DEFAULT_CONVENTION_REGEX));
        properties.add(textProperty(CONVENTION_PREFIX, "Project Id Prefix",
                "Prefix stripped from a conforming group display name to obtain the project id (named mode)",
                DEFAULT_CONVENTION_PREFIX));
        properties.add(textProperty(SELECTION_PARAM, "Selection Parameter",
                "Request parameter carrying the session's project selection (authorize URL + the exchange/refresh POST form bodies)", DEFAULT_SELECTION_PARAM));
        properties.add(textProperty(CLAIM_NAME, "Claim Name",
                "Name of the singular claim emitted on entitlement", DEFAULT_CLAIM_NAME));
        properties.add(textProperty(ENTITLEMENT_ATTRIBUTE, "Entitlement Attribute",
                "User attribute key the fetched entitlement is cached under", DEFAULT_ENTITLEMENT_ATTRIBUTE));
        return properties;
    }

    private static ProviderConfigProperty textProperty(String name, String label, String helpText, String defaultValue) {
        ProviderConfigProperty property = new ProviderConfigProperty();
        property.setName(name);
        property.setLabel(label);
        property.setHelpText(helpText);
        property.setType(ProviderConfigProperty.STRING_TYPE);
        property.setDefaultValue(defaultValue);
        return property;
    }
}
