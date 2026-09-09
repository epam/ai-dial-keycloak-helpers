package com.epam.aidial.keycloak.helpers.model;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class ProjectEntitlementTest {

    private static final String REGEX = "^Project [A-Za-z0-9]+-[A-Za-z0-9]+$";
    private static final String PREFIX = "Project ";

    @Test
    public void namedModeStripsPrefixAndFiltersByConvention() {
        List<ProjectGroup> groups = List.of(
                new ProjectGroup("id-1", "Project EPM-AEM"),
                new ProjectGroup("id-2", "Project ABC-42"),
                new ProjectGroup("id-3", "Project Templates"));

        ProjectEntitlement entitlement = ProjectEntitlement.fromGroups(groups, REGEX, PREFIX);

        assertEquals(ProjectEntitlement.MODE_NAMED, entitlement.getMode());
        assertEquals(List.of("ABC-42", "EPM-AEM"), entitlement.getValues());
    }

    @Test
    public void degradedModeWhenAnyDisplayNameIsNullUsesGroupIds() {
        List<ProjectGroup> groups = List.of(
                new ProjectGroup("id-1", null),
                new ProjectGroup("id-2", "Project EPM-AEM")); // mixed batch — fail conservative

        ProjectEntitlement entitlement = ProjectEntitlement.fromGroups(groups, REGEX, PREFIX);

        assertEquals(ProjectEntitlement.MODE_DEGRADED, entitlement.getMode());
        assertEquals(List.of("id-1", "id-2"), entitlement.getValues());
    }

    @Test
    public void allNullDisplayNamesYieldDegradedIds() {
        List<ProjectGroup> groups = List.of(new ProjectGroup("id-1", null), new ProjectGroup("id-2", null));

        ProjectEntitlement entitlement = ProjectEntitlement.fromGroups(groups, REGEX, PREFIX);

        assertEquals(ProjectEntitlement.MODE_DEGRADED, entitlement.getMode());
        assertEquals(List.of("id-1", "id-2"), entitlement.getValues());
    }

    @Test
    public void valuesAreSortedAndDeduplicated() {
        List<ProjectGroup> groups = List.of(
                new ProjectGroup("id-1", "Project B-2"),
                new ProjectGroup("id-2", "Project A-1"),
                new ProjectGroup("id-3", "Project A-1"));

        ProjectEntitlement entitlement = ProjectEntitlement.fromGroups(groups, REGEX, PREFIX);

        assertEquals(List.of("A-1", "B-2"), entitlement.getValues());
    }

    @Test
    public void emptyGroupListYieldsEmptyNamedEntitlement() {
        ProjectEntitlement entitlement = ProjectEntitlement.fromGroups(List.of(), REGEX, PREFIX);

        assertEquals(ProjectEntitlement.MODE_NAMED, entitlement.getMode());
        assertEquals(List.of(), entitlement.getValues());
    }
}
