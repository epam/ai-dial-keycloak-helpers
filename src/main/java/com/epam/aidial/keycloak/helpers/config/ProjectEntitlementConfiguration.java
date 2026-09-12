package com.epam.aidial.keycloak.helpers.config;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Value
public class ProjectEntitlementConfiguration {

    String conventionRegex;
    String conventionPrefix;
    String selectionParam;
    String claimName;
    String entitlementAttribute;
    long entitlementMaxAgeMinutes;
    int graphConnectTimeoutMillis;
    int graphReadTimeoutMillis;

    private static final String CONVENTION_REGEX = "convention.regex";
    private static final String CONVENTION_PREFIX = "convention.prefix";
    public static final String SELECTION_PARAM = "selection.param";
    private static final String CLAIM_NAME = "claim.name";
    private static final String ENTITLEMENT_ATTRIBUTE = "entitlement.attribute";
    public static final String ENTITLEMENT_MAX_AGE = "entitlement.max-age";
    private static final String DEFAULT_ENTITLEMENT_MAX_AGE = "0";
    public static final String GRAPH_CONNECT_TIMEOUT = "graph.connect.timeout";
    public static final String GRAPH_READ_TIMEOUT = "graph.read.timeout";

    /**
     * The user attribute the fetch freshness timestamp is cached under (epoch-millis;
     * written by the IdP mapper on every successful fetch and every clear, read by the
     * protocol mapper's freshness bound) — a fixed name, not a knob.
     */
    public static final String ENTITLEMENT_TIMESTAMP_ATTRIBUTE = "projectEntitlementAt";

    private static final String DEFAULT_CONVENTION_REGEX = "^Project [A-Za-z0-9]+-[A-Za-z0-9]+$";
    private static final String DEFAULT_CONVENTION_PREFIX = "Project ";
    private static final String DEFAULT_SELECTION_PARAM = "project";
    private static final String DEFAULT_CLAIM_NAME = "project";
    private static final String DEFAULT_ENTITLEMENT_ATTRIBUTE = "projectEntitlement";

    /** Defaults mirror {@link com.epam.aidial.keycloak.helpers.provider.MsGraphProjectGroupsProvider}. */
    private static final String DEFAULT_GRAPH_CONNECT_TIMEOUT = "5000";
    private static final String DEFAULT_GRAPH_READ_TIMEOUT = "10000";

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
                config.getOrDefault(ENTITLEMENT_ATTRIBUTE, DEFAULT_ENTITLEMENT_ATTRIBUTE),
                parseMaxAgeMinutes(config.get(ENTITLEMENT_MAX_AGE)),
                parseTimeoutMillis(GRAPH_CONNECT_TIMEOUT, config.get(GRAPH_CONNECT_TIMEOUT), DEFAULT_GRAPH_CONNECT_TIMEOUT),
                parseTimeoutMillis(GRAPH_READ_TIMEOUT, config.get(GRAPH_READ_TIMEOUT), DEFAULT_GRAPH_READ_TIMEOUT)
        );
    }

    /**
     * The freshness bound in minutes, leniently parsed — an unset or unparsible
     * value means the default {@code 0} (the bound disabled). Never fails the mapper.
     */
    private static long parseMaxAgeMinutes(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < 0) {
                log.warn("Invalid '{}' config value '{}' — using 0 (the freshness bound disabled)", ENTITLEMENT_MAX_AGE, value);
                return 0L;
            }
            return parsed;
        } catch (NumberFormatException e) {
            log.warn("Invalid '{}' config value '{}' — treating as 0 (the freshness bound disabled)", ENTITLEMENT_MAX_AGE, value);
            return 0L;
        }
    }

    /**
     * A Graph HTTP timeout in milliseconds, leniently parsed — an unset, unparsible,
     * or non-positive value means the given default (a non-positive timeout is
     * undefined for {@code HttpURLConnection}: zero reads as INFINITE, negative
     * throws). Never fails the mapper.
     */
    private static int parseTimeoutMillis(String key, String value, String defaultValue) {
        if (value == null || value.isEmpty()) {
            return Integer.parseInt(defaultValue);
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0 || parsed > Integer.MAX_VALUE) {
                log.warn("Invalid '{}' config value '{}' — a timeout must be a positive number of milliseconds; using the default {} ms", key, value, defaultValue);
                return Integer.parseInt(defaultValue);
            }
            return (int) parsed;
        } catch (NumberFormatException e) {
            log.warn("Invalid '{}' config value '{}' — using the default {} ms", key, value, defaultValue);
            return Integer.parseInt(defaultValue);
        }
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
                "Prefix stripped from a conforming group display name to obtain the project id (the per-group fallback)",
                DEFAULT_CONVENTION_PREFIX));
        properties.add(textProperty(SELECTION_PARAM, "Selection Parameter",
                "Request parameter carrying the session's project selection (authorize URL + the exchange/refresh POST form bodies)", DEFAULT_SELECTION_PARAM));
        properties.add(textProperty(CLAIM_NAME, "Claim Name",
                "Name of the singular claim emitted on entitlement", DEFAULT_CLAIM_NAME));
        properties.add(textProperty(ENTITLEMENT_ATTRIBUTE, "Entitlement Attribute",
                "User attribute key the fetched entitlement is cached under", DEFAULT_ENTITLEMENT_ATTRIBUTE));
        properties.add(textProperty(ENTITLEMENT_MAX_AGE, "Entitlement Max Age (minutes)",
                "Freshness bound for the cached entitlement, in minutes (protocol mapper): a cache older than this — "
                        + "or without a fetch timestamp — yields no claim; 0 = disabled. Set above the realm's SSO Session Max",
                DEFAULT_ENTITLEMENT_MAX_AGE));
        properties.add(textProperty(GRAPH_CONNECT_TIMEOUT, "Graph Connect Timeout (ms)",
                "Connect timeout for the Microsoft Graph calls, in milliseconds (fetch mapper)", DEFAULT_GRAPH_CONNECT_TIMEOUT));
        properties.add(textProperty(GRAPH_READ_TIMEOUT, "Graph Read Timeout (ms)",
                "Read timeout for the Microsoft Graph calls, in milliseconds (fetch mapper)", DEFAULT_GRAPH_READ_TIMEOUT));
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
