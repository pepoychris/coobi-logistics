package com.coobi.logistics.eventgenerator.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MVP-1.5: the target rate is met on average and every batch stays bounded. */
class PublishRatePlannerTest {

    @Test
    void spreadsTheNormalModeTargetOverTicks() {
        PublishRatePlanner planner = new PublishRatePlanner();
        int total = 0;

        // 1,000 events/s over a 100 ms tick is 100 events per tick.
        for (int tick = 0; tick < 10; tick++) {
            int batchSize = planner.planBatch(1000, 100, 500);
            assertThat(batchSize).isEqualTo(100);
            total += batchSize;
        }

        assertThat(total).isEqualTo(1000);
        assertThat(planner.pendingBudget()).isZero();
    }

    @Test
    void spreadsAOneSecondTickOverASingleBatch() {
        assertThat(new PublishRatePlanner().planBatch(1000, 1000, 2000)).isEqualTo(1000);
    }

    @Test
    void keepsTheAverageOfANonDivisibleTarget() {
        PublishRatePlanner planner = new PublishRatePlanner();
        int total = 0;

        for (int tick = 0; tick < 100; tick++) {
            total += planner.planBatch(1234, 100, 200);
        }

        assertThat(total).isBetween(12339, 12340);
    }

    @Test
    void carriesTheFractionIntoTheNextTick() {
        PublishRatePlanner planner = new PublishRatePlanner();
        // 35 events/s over a 100 ms tick is 3.5 events per tick: the fraction is carried,
        // so the batches alternate instead of being truncated on every tick.
        List<Integer> batches = new ArrayList<>();
        int total = 0;

        for (int tick = 0; tick < 20; tick++) {
            int batchSize = planner.planBatch(35, 100, 10);
            batches.add(batchSize);
            total += batchSize;
        }

        assertThat(batches).contains(3, 4);
        assertThat(total).isEqualTo(70);
    }

    @Test
    void neverExceedsTheConfiguredBatchBound() {
        PublishRatePlanner planner = new PublishRatePlanner();

        for (int tick = 0; tick < 20; tick++) {
            int batchSize = planner.planBatch(100_000, 100, 250);
            assertThat(batchSize).isEqualTo(250);
        }

        // Surplus budget is discarded instead of growing without bound.
        assertThat(planner.pendingBudget()).isLessThanOrEqualTo(250.0);
    }

    @Test
    void returnsZeroWhenTheTargetIsZero() {
        assertThat(new PublishRatePlanner().planBatch(0, 100, 10)).isZero();
    }

    @Test
    void rejectsInvalidArguments() {
        PublishRatePlanner planner = new PublishRatePlanner();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> planner.planBatch(-1, 100, 10));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> planner.planBatch(100, 0, 10));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> planner.planBatch(100, 100, 0));
    }
}
