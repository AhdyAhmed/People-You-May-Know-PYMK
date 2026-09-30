package com.ahdyahmed.pymk.datagen;

import com.ahdyahmed.pymk.datagen.config.DataGenProperties;
import com.ahdyahmed.pymk.datagen.generate.ConnectionTimelineGenerator;
import com.ahdyahmed.pymk.datagen.generate.ConnectionTimelineGenerator.TimestampedEdge;
import com.ahdyahmed.pymk.datagen.generate.EventGenerator;
import com.ahdyahmed.pymk.datagen.generate.EventGenerator.EventRecord;
import com.ahdyahmed.pymk.datagen.generate.MemberFactory;
import com.ahdyahmed.pymk.datagen.generate.MemberFactory.MemberRecord;
import com.ahdyahmed.pymk.datagen.generate.OrganizationPool;
import com.ahdyahmed.pymk.datagen.generate.PlaceholderEmbeddingFactory;
import com.ahdyahmed.pymk.datagen.generate.PreferentialAttachmentGraphGenerator;
import com.ahdyahmed.pymk.datagen.generate.PreferentialAttachmentGraphGenerator.Edge;
import com.ahdyahmed.pymk.datagen.load.BulkLoader;
import com.ahdyahmed.pymk.datagen.load.BulkLoader.MemberEmbeddingRecord;
import com.ahdyahmed.pymk.datagen.report.SummaryReporter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.datafaker.Faker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Orchestrates the whole Day 4 pipeline: generate members, generate a
 * preferential-attachment graph, generate events, bulk-load everything into
 * Postgres, then print a summary. Runs once and exits (see
 * PymkDataGenApplication).
 *
 * <p>Same seed + same member count always produces the same dataset -
 * useful for reproducing a bug against "the exact same graph".</p>
 */
@Component
public class SeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final DataGenProperties props;
    private final BulkLoader loader;
    private final SummaryReporter reporter;

    public SeedRunner(DataGenProperties props, BulkLoader loader, SummaryReporter reporter) {
        this.props = props;
        this.loader = loader;
        this.reporter = reporter;
    }

    @Override
    public void run(String... args) {
        long overallStart = System.nanoTime();
        log.info("Starting PYMK synthetic data generation: members={} avgDegree={} eventsPerMember={} seed={}",
                props.getMembers(), props.getAvgDegree(), props.getEventsPerMember(), props.getSeed());

        Random masterRandom = new Random(props.getSeed());
        Random graphRandom = new Random(masterRandom.nextLong());
        Random timelineRandom = new Random(masterRandom.nextLong());
        Random eventRandom = new Random(masterRandom.nextLong());
        Random embeddingRandom = new Random(masterRandom.nextLong());
        Faker faker = new Faker(new Random(masterRandom.nextLong()));
        Instant now = Instant.now();

        if (props.isTruncateExisting()) {
            loader.truncateAll();
        }

        List<MemberRecord> members = timed("Generating members", () -> {
            OrganizationPool companies = OrganizationPool.companies(faker, props.getOrganizationPoolSize());
            OrganizationPool schools = OrganizationPool.schools(faker, props.getOrganizationPoolSize());
            OrganizationPool regions = OrganizationPool.regions(faker, Math.max(20, props.getOrganizationPoolSize() / 5));
            MemberFactory factory = new MemberFactory(faker, companies, schools, regions, masterRandom, now);

            List<MemberRecord> result = new ArrayList<>(props.getMembers());
            for (long id = 1; id <= props.getMembers(); id++) {
                result.add(factory.create(id));
            }
            return result;
        });
        timed("Loading members", () -> loader.insertMembers(members));

        List<Edge> edges = timed("Generating connection graph (preferential attachment)", () -> {
            int edgesPerNewMember = Math.max(1, props.getAvgDegree() / 2);
            return new PreferentialAttachmentGraphGenerator(edgesPerNewMember, graphRandom).generate(props.getMembers());
        });
        List<TimestampedEdge> timestampedEdges = timed("Assigning connection timestamps", () ->
                new ConnectionTimelineGenerator(timelineRandom).assignTimestamps(edges, now, 720));
        timed("Loading connections", () -> loader.insertConnections(timestampedEdges));

        long targetEventCount = Math.round(props.getMembers() * props.getEventsPerMember());
        List<EventRecord> events = timed("Generating events", () ->
                new EventGenerator(eventRandom).generate(props.getMembers(), timestampedEdges, targetEventCount, now));
        timed("Loading events", () -> loader.insertEvents(events));

        List<MemberEmbeddingRecord> embeddingRows = timed("Generating placeholder embeddings", () -> {
            PlaceholderEmbeddingFactory embeddingFactory = new PlaceholderEmbeddingFactory(embeddingRandom);
            List<MemberEmbeddingRecord> result = new ArrayList<>(props.getMembers());
            for (long id = 1; id <= props.getMembers(); id++) {
                result.add(new MemberEmbeddingRecord(id, embeddingFactory.create(), now));
            }
            return result;
        });
        timed("Loading embeddings", () -> loader.insertEmbeddings(embeddingRows));

        reporter.report();

        double totalSeconds = (System.nanoTime() - overallStart) / 1_000_000_000.0;
        log.info("Done in {} s: {} members, {} undirected edges, {} events, {} embeddings",
                String.format("%.1f", totalSeconds), members.size(), edges.size(), events.size(), embeddingRows.size());
    }

    private <T> T timed(String label, java.util.function.Supplier<T> work) {
        long start = System.nanoTime();
        T result = work.get();
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        log.info("{}: {} s", label, String.format("%.1f", seconds));
        return result;
    }

    private void timed(String label, Runnable work) {
        timed(label, () -> {
            work.run();
            return null;
        });
    }
}
