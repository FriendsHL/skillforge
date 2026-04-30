package com.skillforge.server.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RescanReportTest {

    @Test
    @DisplayName("empty has all-zero counts")
    void empty_isZero() {
        RescanReport r = RescanReport.empty();
        assertThat(r.created()).isZero();
        assertThat(r.updated()).isZero();
        assertThat(r.missing()).isZero();
        assertThat(r.invalid()).isZero();
        assertThat(r.shadowed()).isZero();
        assertThat(r.disabledDuplicates()).isZero();
    }

    @Test
    @DisplayName("plus sums field-wise")
    void plus_sumsFields() {
        RescanReport a = new RescanReport(1, 2, 3, 4, 5, 6);
        RescanReport b = new RescanReport(10, 20, 30, 40, 50, 60);
        RescanReport sum = a.plus(b);
        assertThat(sum.created()).isEqualTo(11);
        assertThat(sum.updated()).isEqualTo(22);
        assertThat(sum.missing()).isEqualTo(33);
        assertThat(sum.invalid()).isEqualTo(44);
        assertThat(sum.shadowed()).isEqualTo(55);
        assertThat(sum.disabledDuplicates()).isEqualTo(66);
    }

    @Test
    @DisplayName("addX increments only the targeted field")
    void addX_isolated() {
        RescanReport r = RescanReport.empty()
                .addCreated(1)
                .addUpdated(2)
                .addMissing(3)
                .addInvalid(4)
                .addShadowed(5)
                .addDisabledDuplicates(6);
        assertThat(r).isEqualTo(new RescanReport(1, 2, 3, 4, 5, 6));
    }
}
