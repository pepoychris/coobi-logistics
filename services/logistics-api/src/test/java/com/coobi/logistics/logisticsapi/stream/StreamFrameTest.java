package com.coobi.logistics.logisticsapi.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The wire format of the streams: the frames the documentation promises, asserted as the text a
 * client reads.
 */
class StreamFrameTest {

    @Test
    void writesAnEventAsItsNameAndItsData() {
        assertThat(StreamFrame.event("alert", "{\"vehicleId\":\"TRUCK-00001\"}").wireText())
                .isEqualTo("event:alert\ndata:{\"vehicleId\":\"TRUCK-00001\"}\n\n");
    }

    @Test
    void writesCommentsThatAClientIgnoresAsEvents() {
        assertThat(StreamFrame.keepAlive().wireText()).isEqualTo(":keep-alive\n\n");
        assertThat(StreamFrame.comment("anything").wireText()).isEqualTo(":anything\n\n");
    }

    @Test
    void opensAConnectionWithAReconnectHint() {
        assertThat(StreamFrame.opened().wireText()).isEqualTo("retry:3000\n:connected\n\n");
    }

    @Test
    void refusesAFrameThatIsNeitherAnEventNorAComment() {
        assertThatThrownBy(() -> new StreamFrame("alert", null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payload");
    }
}
