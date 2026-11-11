package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.net.URI;
import javax.net.ssl.HttpsURLConnection;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import static com.lending.ai.Json.*;

/** Searches recent public economic releases without sending analyst text or customer data to publishers. */
final class EconomicNews {
    private record Feed(String name, String url, String articleHost) {}
    private record Release(String source, String title, String summary, String url, Instant published) {}
    private static final List<Feed> FEEDS = List.of(
        new Feed("Federal Reserve", "https://www.federalreserve.gov/feeds/press_monetary.xml", "www.federalreserve.gov"),
        new Feed("Federal Reserve", "https://www.federalreserve.gov/feeds/g19.xml", "www.federalreserve.gov"),
        new Feed("Federal Reserve", "https://www.federalreserve.gov/feeds/sloos.xml", "www.federalreserve.gov"),
        new Feed("Bureau of Economic Analysis", "https://apps.bea.gov/rss/rss.xml", "www.bea.gov")
    );
    private static final Set<String> ECONOMIC_TERMS = Set.of(
        "rates", "credit", "lending", "loan", "inflation", "prices", "employment",
        "unemployment", "gdp", "income", "spending", "mortgage", "economy", "monetary", "fomc", "trade");

    JsonNode search(String question) {
        Instant now = Instant.now();
        var terms = new HashSet<>(Arrays.asList(question.toLowerCase(Locale.ROOT).split("\\W+")));
        terms.retainAll(ECONOMIC_TERMS);
        var requests = FEEDS.stream().map(feed -> CompletableFuture.supplyAsync(() -> read(feed))).toList();
        var results = requests.stream().map(CompletableFuture::join).toList();
        var releases = results.stream().flatMap(Optional::stream).flatMap(Collection::stream)
            .filter(release -> !release.published().isAfter(now) && release.published().isAfter(now.minus(Duration.ofDays(90))))
            .filter(release -> relevance(release, ECONOMIC_TERMS) > 0)
            .distinct().sorted(Comparator.<Release>comparingInt(release -> relevance(release, terms)).reversed()
                .thenComparing(Release::published, Comparator.reverseOrder()))
            .limit(8).map(release -> obj("evidenceId","news:" + hash(release.url()),"title",release.title(),
                "summary",release.summary(),"url",release.url(),"publishedAt",release.published().toString(),"publisher",release.source()))
            .toList();
        return obj("checkedAt",now.toString(),"sourcesReached",results.stream().filter(Optional::isPresent).count(),
            "items",releases,"basis","Public economic headlines provide context for hypotheses about recorded outcomes.");
    }

    private static int relevance(Release release, Set<String> terms) {
        var words = Arrays.asList(release.title().toLowerCase(Locale.ROOT).split("\\W+"));
        return (int) terms.stream().filter(words::contains).count();
    }

    private Optional<List<Release>> read(Feed feed) {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) URI.create(feed.url()).toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","LendingStudio/1.0 economic evidence reader");
            if (connection.getResponseCode() != 200) return Optional.empty();
            try (var body = connection.getInputStream()) {
                byte[] xml = body.readNBytes(1_000_001);
                if (xml.length > 1_000_000) return Optional.empty();
                var factory = DocumentBuilderFactory.newInstance();
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
                var items = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getElementsByTagName("item");
                var result = new ArrayList<Release>();
                for (int index = 0; index < Math.min(100,items.getLength()); index++) {
                    var item = (Element) items.item(index);
                    try {
                        String title = content(item,"title");
                        URI link = URI.create(content(item,"link"));
                        String host = link.getHost();
                        if (title.isBlank() || title.length() > 250 || !"https".equals(link.getScheme()) || host == null ||
                            !host.equals(feed.articleHost())) continue;
                        String date = content(item,"pubDate").replaceFirst(" EST$"," -0500").replaceFirst(" EDT$"," -0400");
                        Instant published = ZonedDateTime.parse(date,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                        String summary = content(item,"description").replaceAll("<[^>]*>"," ").replaceAll("\\s+"," ").strip();
                        if (summary.length() > 450) summary = summary.substring(0,450) + "…";
                        result.add(new Release(feed.name(),title,summary,link.toString(),published));
                    } catch (RuntimeException ignored) { /* One malformed release does not invalidate the feed. */ }
                }
                return Optional.of(result);
            }
        } catch (Exception unavailable) {
            return Optional.empty();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String content(Element item, String tag) {
        var values = item.getElementsByTagName(tag);
        return values.getLength() == 0 ? "" : values.item(0).getTextContent().strip();
    }
}
