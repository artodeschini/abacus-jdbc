package com.landawn.abacus.samples;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.Profiler;

public class PerformanceTestByProfiler extends Jdbc {

    @Test
    public void test_01() {
        Profiler.run(1, 100, 3, "crud_by_Jdbc", () -> crud_by_Jdbc()).printResult();

        Profiler.run(1, 100, 3, "crud_by_SQLExecutor", () -> crud_by_SQLExecutor()).printResult();
    }

}