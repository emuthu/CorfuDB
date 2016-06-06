package org.corfudb.runtime.view;

import lombok.Getter;
import org.corfudb.infrastructure.LayoutServer;
import org.corfudb.infrastructure.LogUnitServer;
import org.corfudb.infrastructure.SequencerServer;
import org.corfudb.protocols.wireprotocol.IMetadata;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.clients.SequencerClient;
import org.corfudb.runtime.collections.SMRMap;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Created by mwei on 1/8/16.
 */
public class StreamViewTest extends AbstractViewTest {

    @Getter
    final String defaultConfigurationString = getDefaultEndpoint();

    @Test
    @SuppressWarnings("unchecked")
    public void canReadWriteFromStream()
            throws Exception {
        // default layout is chain replication.
        addServerForTest(getDefaultEndpoint(), new LayoutServer(defaultOptionsMap()));
        addServerForTest(getDefaultEndpoint(), new LogUnitServer(defaultOptionsMap()));
        addServerForTest(getDefaultEndpoint(), new SequencerServer(defaultOptionsMap()));
        wireRouters();

        //begin tests
        CorfuRuntime r = getRuntime().connect();
        UUID streamA = UUID.nameUUIDFromBytes("stream A".getBytes());
        byte[] testPayload = "hello world".getBytes();

        StreamView sv = r.getStreamsView().get(streamA);
        sv.write(testPayload);

        assertThat(sv.read().getPayload())
                .isEqualTo("hello world".getBytes());

        assertThat(sv.read())
                .isEqualTo(null);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void canReadWriteManyFromStream()
            throws Exception {
        //begin tests
        CorfuRuntime r = getDefaultRuntime();
        UUID streamA = UUID.nameUUIDFromBytes("stream A".getBytes());
        byte[] testPayload = "hello world".getBytes();

        StreamView sv = r.getStreamsView().get(streamA);
        for (int i = 0; i < 100_000; i++) {
            sv.write(testPayload);

            assertThat(sv.read().getPayload())
                    .isEqualTo("hello world".getBytes());
        }

        assertThat(sv.read())
                .isEqualTo(null);
    }


    @Test
    @SuppressWarnings("unchecked")
    public void canReadWriteFromStreamConcurrent()
            throws Exception {
        // default layout is chain replication.
        addServerForTest(getDefaultEndpoint(), new LayoutServer(defaultOptionsMap()));
        addServerForTest(getDefaultEndpoint(), new LogUnitServer(defaultOptionsMap()));
        addServerForTest(getDefaultEndpoint(), new SequencerServer(defaultOptionsMap()));
        wireRouters();

        //begin tests
        CorfuRuntime r = getRuntime().connect();
        UUID streamA = UUID.nameUUIDFromBytes("stream A".getBytes());
        byte[] testPayload = "hello world".getBytes();

        StreamView sv = r.getStreamsView().get(streamA);
        scheduleConcurrently(100, i -> sv.write(testPayload));
        executeScheduled(8, 10, TimeUnit.SECONDS);

        scheduleConcurrently(100, i-> assertThat(sv.read().getPayload())
                .isEqualTo("hello world".getBytes()));
        executeScheduled(8, 10, TimeUnit.SECONDS);
        assertThat(sv.read())
                .isEqualTo(null);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void canReadWriteFromStreamWithoutBackpointers()
            throws Exception {
        // default layout is chain replication.
        addServerForTest(getDefaultEndpoint(), new LayoutServer(defaultOptionsMap()));
        addServerForTest(getDefaultEndpoint(), new LogUnitServer(defaultOptionsMap()));
        addServerForTest(getDefaultEndpoint(), new SequencerServer(defaultOptionsMap()));
        wireRouters();

        //begin tests
        CorfuRuntime r = getRuntime()
                .setBackpointersDisabled(true)
                .connect();

        UUID streamA = UUID.nameUUIDFromBytes("stream A".getBytes());
        byte[] testPayload = "hello world".getBytes();

        StreamView sv = r.getStreamsView().get(streamA);
        scheduleConcurrently(100, i -> sv.write(testPayload));
        executeScheduled(8, 10, TimeUnit.SECONDS);

        scheduleConcurrently(100, i-> assertThat(sv.read().getPayload())
                .isEqualTo("hello world".getBytes()));
        executeScheduled(8, 10, TimeUnit.SECONDS);
        assertThat(sv.read())
                .isEqualTo(null);
    }



    @Test
    @SuppressWarnings("unchecked")
    public void canReadWriteFromCachedStream()
            throws Exception {
        // default layout is chain replication.
        addServerForTest(getDefaultEndpoint(), new LayoutServer(defaultOptionsMap()));
        addServerForTest(getDefaultEndpoint(), new LogUnitServer(defaultOptionsMap()));
        addServerForTest(getDefaultEndpoint(), new SequencerServer(defaultOptionsMap()));
        wireRouters();

        //begin tests
        CorfuRuntime r = getRuntime().connect()
                .setCacheDisabled(false);
        UUID streamA = UUID.nameUUIDFromBytes("stream A".getBytes());
        byte[] testPayload = "hello world".getBytes();

        StreamView sv = r.getStreamsView().get(streamA);
        sv.write(testPayload);

        assertThat(sv.read().getPayload())
                .isEqualTo("hello world".getBytes());

        assertThat(sv.read())
                .isEqualTo(null);
    }

        @Test
    @SuppressWarnings("unchecked")
    public void streamCanSurviveOverwriteException()
            throws Exception {
        // default layout is chain replication.
        addServerForTest(getDefaultEndpoint(), new LayoutServer(defaultOptionsMap()));
        addServerForTest(getDefaultEndpoint(), new LogUnitServer(defaultOptionsMap()));
        addServerForTest(getDefaultEndpoint(), new SequencerServer(defaultOptionsMap()));
        wireRouters();

        //begin tests
        CorfuRuntime r = getRuntime().connect();
        UUID streamA = CorfuRuntime.getStreamID("stream A");
        byte[] testPayload = "hello world".getBytes();

        // write without reserving a token
        r.getAddressSpaceView().fillHole(0);

        // Write to the stream, and read back. The hole should be filled.
        StreamView sv = r.getStreamsView().get(streamA);
        sv.write(testPayload);

        assertThat(sv.read().getPayload())
                .isEqualTo("hello world".getBytes());

        assertThat(sv.read())
                .isEqualTo(null);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void streamWillHoleFill()
            throws Exception {
        // default layout is chain replication.
        addServerForTest(getDefaultEndpoint(), new LayoutServer(defaultOptionsMap()));
        addServerForTest(getDefaultEndpoint(), new LogUnitServer(defaultOptionsMap()));
        addServerForTest(getDefaultEndpoint(), new SequencerServer(defaultOptionsMap()));
        wireRouters();

        //begin tests
        CorfuRuntime r = getRuntime().connect();
        UUID streamA = CorfuRuntime.getStreamID("stream A");
        byte[] testPayload = "hello world".getBytes();

        // Generate a hole.
        r.getSequencerView().nextToken(Collections.singleton(streamA), 1);

        // Write to the stream, and read back. The hole should be filled.
        StreamView sv = r.getStreamsView().get(streamA);
        sv.write(testPayload);

        assertThat(sv.read().getPayload())
                .isEqualTo("hello world".getBytes());

        assertThat(sv.read())
                .isEqualTo(null);
    }



    @Test
    @SuppressWarnings("unchecked")
    public void streamWithHoleFill()
            throws Exception {
        CorfuRuntime r = getDefaultRuntime();
        UUID streamA = CorfuRuntime.getStreamID("stream A");

        byte[] testPayload = "hello world".getBytes();
        byte[] testPayload2 = "hello world2".getBytes();

        StreamView sv = r.getStreamsView().get(streamA);
        sv.write(testPayload);

        //generate a stream hole
        SequencerClient.TokenResponse tr =
                r.getSequencerView().nextToken(Collections.singleton(streamA), 1);
        r.getAddressSpaceView().fillHole(tr.getToken());

        tr = r.getSequencerView().nextToken(Collections.singleton(streamA), 1);
        r.getAddressSpaceView().fillHole(tr.getToken());

        sv.write(testPayload2);

        //make sure we can still read the stream.
        assertThat(sv.read().getPayload())
                .isEqualTo(testPayload);

        assertThat(sv.read().getPayload())
                .isEqualTo(testPayload2);
    }
}
