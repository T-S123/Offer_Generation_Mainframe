/**
 * Customer/marketing persistence atomically journals Business publication with catalog revisions; customer
 * writes retain contacts and pipeline admission.
 */
package com.lending.engine.infrastructure;

import com.lending.engine.application.Ports.*;
import com.lending.engine.domain.Model.*;
import com.lending.engine.marketing.domain.Marketing.*;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/**
 * Customer/marketing persistence atomically journals Business publication with catalog revisions; customer
 * writes retain contacts and pipeline admission.
 */
public final class SqlStore implements Store {
    private final Connection connection;
    /** Initializes SQL store with the supplied configuration and dependencies. */
    public SqlStore(String url) {
        try {
            connection = DriverManager.getConnection(url, "sa", "");
            try (Statement s = connection.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS customers (id VARCHAR(40) PRIMARY KEY, external_ref VARCHAR(60) UNIQUE, batch_id VARCHAR(40), payload CLOB NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS customer_batch ON customers(batch_id)");
                s.execute("CREATE TABLE IF NOT EXISTS imports (source_system VARCHAR(80), source_id VARCHAR(120), payload CLOB NOT NULL, PRIMARY KEY(source_system,source_id))");
                s.execute("CREATE TABLE IF NOT EXISTS jobs (id VARCHAR(40) PRIMARY KEY, payload CLOB NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS populations (id VARCHAR(40) PRIMARY KEY, payload CLOB NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS members (population_id VARCHAR(40) REFERENCES populations(id), customer_id VARCHAR(40) REFERENCES customers(id), product VARCHAR(20), profile_version INT NOT NULL, decision_id VARCHAR(40) NOT NULL, PRIMARY KEY(population_id,customer_id,product))");
            }
            SchemaMigrations.apply(connection);
            transaction(s->{((SqlSession)s).backfillContacts();return null;});
        } catch (SQLException e) { throw new IllegalStateException("Cannot open local database", e); }
    }
    /** Runs a read-only customer and marketing unit of work. */
    public synchronized <T> T read(Function<Session,T> action) { return transaction(action); }
    /** Runs a customer and marketing write transaction with rollback on failure. */
    public synchronized <T> T write(Function<Session,T> action) { return transaction(action); }
    /** Creates a store session for the requested transaction and commits or rolls back the action. */
    private <T> T transaction(Function<Session,T> action) {
        try {
            connection.setAutoCommit(false);
            T value = action.apply(new SqlSession());
            connection.commit();
            return value;
        } catch (RuntimeException | SQLException e) {
            try { connection.rollback(); } catch (SQLException suppressed) { e.addSuppressed(suppressed); }
            if (e instanceof RuntimeException r) throw r;
            throw new IllegalStateException("Database transaction failed", e);
        }
    }
    /** Releases the resources owned by this component. */
    public synchronized void close() { try { connection.close(); } catch (SQLException e) { throw new IllegalStateException(e); } }
    /** Groups SQL session behavior for SQL store operations. */
    private final class SqlSession implements Session {
        /** Retrieves the publication receipt associated with a reviewed simulation run. */
        public com.lending.engine.simulation.domain.Simulation.Publication publication(String id){return latest("PUBLICATION",id,com.lending.engine.simulation.domain.Simulation.Publication.class);}
        /** Persists the receipt linking reviewed simulation evidence to a published catalog change. */
        public void savePublication(com.lending.engine.simulation.domain.Simulation.Publication p){revision("PUBLICATION",p.runId(),1,p);}
        /** Loads the latest revision of a versioned catalog record. */
        private <T> T latest(String kind,String id,Class<T> type) {
            return one("SELECT payload FROM marketing_revisions WHERE kind=? AND id=? ORDER BY version DESC LIMIT 1",type,kind,id);
        }
        /** Loads the current revisions of all records in a catalog category. */
        private <T> List<T> latestAll(String kind,Class<T> type) {
            return records("SELECT r.payload FROM marketing_revisions r WHERE kind=? AND version=(SELECT MAX(x.version) FROM marketing_revisions x WHERE x.kind=r.kind AND x.id=r.id) ORDER BY id",type,kind);
        }
        /** Loads the retained revisions of a versioned catalog record. */
        private <T> List<T> history(String kind,String id,Class<T> type) {
            return records("SELECT payload FROM marketing_revisions WHERE kind=? AND id=? ORDER BY version",type,kind,id);
        }
        /** Returns the current catalog revision used to detect stale qualification previews. */
        private void revision(String kind,String id,int version,Object value) { execute("INSERT INTO marketing_revisions VALUES(?,?,?,?)",kind,id,version,Json.write(value)); }
        /** Retrieves the current approved catalog offer. */
        public Offer offer(String id){return latest("OFFER",id,Offer.class);}
        /** Lists the current approved catalog offers. */
        public List<Offer> offers(){return latestAll("OFFER",Offer.class);}
        /** Persists a new catalog offer revision while retaining its history. */
        public void saveOffer(Offer o){revision("OFFER",o.id(),o.version(),o);}
        /** Returns the immutable revision history of a catalog offer. */
        public List<Offer> offerHistory(String id){return history("OFFER",id,Offer.class);}
        /** Retrieves the current campaign configuration. */
        public Campaign campaign(String id){return latest("CAMPAIGN",id,Campaign.class);}
        /** Lists the current campaign configurations. */
        public List<Campaign> campaigns(){return latestAll("CAMPAIGN",Campaign.class);}
        /** Persists a new campaign revision while retaining its history. */
        public void saveCampaign(Campaign c){revision("CAMPAIGN",c.id(),c.version(),c);}
        /** Returns the immutable revision history of a campaign. */
        public List<Campaign> campaignHistory(String id){return history("CAMPAIGN",id,Campaign.class);}
        /** Returns the active policy configuration used by the workflow. */
        public Policy policy(){return latest("POLICY","GLOBAL",Policy.class);}
        /** Persists a new marketing policy revision while retaining its history. */
        public void savePolicy(Policy p){revision("POLICY","GLOBAL",p.version(),p);}
        /** Returns the revision history of the marketing policy. */
        public List<Policy> policyHistory(){return history("POLICY","GLOBAL",Policy.class);}
        /** Retrieves the current suppression entry for a customer. */
        public Suppression suppression(String id){return latest("SUPPRESSION",id,Suppression.class);}
        /** Lists configured customer suppression entries. */
        public List<Suppression> suppressions(){return latestAll("SUPPRESSION",Suppression.class);}
        /** Persists a new customer suppression revision while retaining its history. */
        public void saveSuppression(Suppression s){revision("SUPPRESSION",s.customerId(),s.version(),s);}
        /** Returns the revision history of a customer suppression entry. */
        public List<Suppression> suppressionHistory(String id){return history("SUPPRESSION",id,Suppression.class);}
        /** Loads the requested marketing qualification run. */
        public Run marketingRun(String id){return one("SELECT payload FROM marketing_runs WHERE id=?",Run.class,id);}
        /** Finds the marketing run associated with an idempotent request identifier. */
        public Run requestedRun(String id){return one("SELECT payload FROM marketing_runs WHERE request_id=?",Run.class,id);}
        /** Persists the qualification run state and its audit information. */
        public void saveRun(Run r){execute("MERGE INTO marketing_runs KEY(id) VALUES(?,?,?,?)",r.id(),r.request().requestId(),r.createdAt(),Json.write(r));}
        /** Lists stored marketing qualification runs. */
        public Page<RunSummary> marketingRuns(int offset,int limit) {
            long total;
            try(var p=statement("SELECT COUNT(*) FROM marketing_runs");var r=p.executeQuery()){r.next();total=r.getLong(1);}catch(SQLException e){throw new IllegalStateException(e);}
            var rows=records("SELECT payload FROM marketing_runs ORDER BY created_at DESC,id LIMIT ? OFFSET ?",Run.class,limit,offset).stream()
                .map(r->new RunSummary(r.id(),r.request().populationId(),r.status(),r.createdAt(),r.qualifiedCustomers(),r.qualifiedOffers(),r.reservationCount())).toList();
            return new Page<>(rows,total,offset,limit);
        }
        /** Retrieves the offer qualification results belonging to a marketing run. */
        public List<Qualification> qualifications(String id){return records("SELECT payload FROM marketing_results WHERE run_id=? ORDER BY ordinal",Qualification.class,id);}
        /** Returns qualification results associated with a Risk ID. */
        public List<Qualification> qualificationsForRisk(String runId,String riskId){return records("SELECT payload FROM marketing_results WHERE run_id=? AND risk_id=? ORDER BY ordinal",Qualification.class,runId,riskId);}
        /** Returns a bounded page of qualifications with stable pagination. */
        public List<com.lending.engine.bureau.domain.Bureau.SourceRow> qualificationPage(String runId,int after,int limit){
            try(var p=statement("SELECT ordinal,payload FROM marketing_results WHERE run_id=? AND ordinal>? ORDER BY ordinal LIMIT ?",runId,after,limit);var r=p.executeQuery()){
                var rows=new ArrayList<com.lending.engine.bureau.domain.Bureau.SourceRow>();
                while(r.next())rows.add(new com.lending.engine.bureau.domain.Bureau.SourceRow(r.getInt(1),Json.read(r.getString(2),Qualification.class)));return rows;
            }catch(SQLException e){throw new IllegalStateException(e);}
        }
        /** Retrieves the reservations backing a finalized qualification source. */
        public List<Reservation> sourceReservations(String runId,String customerId,String campaignId){return records("SELECT payload FROM marketing_reservations WHERE run_id=? AND customer_id=? AND campaign_id=?",Reservation.class,runId,customerId,campaignId);}
        /** Scans finalized qualifications for downstream pre-screen offer recovery. */
        public List<com.lending.engine.marketing.application.MarketingPorts.Repository.StoredMatch> qualificationScan(String afterRun,int afterOrdinal,int limit){
            try(var p=statement("SELECT run_id,ordinal,payload FROM marketing_results WHERE run_id>? OR (run_id=? AND ordinal>?) ORDER BY run_id,ordinal LIMIT ?",afterRun,afterRun,afterOrdinal,limit);var r=p.executeQuery()){
                var rows=new ArrayList<com.lending.engine.marketing.application.MarketingPorts.Repository.StoredMatch>();while(r.next())rows.add(new com.lending.engine.marketing.application.MarketingPorts.Repository.StoredMatch(r.getString(1),r.getInt(2),Json.read(r.getString(3),Qualification.class)));return rows;
            }catch(SQLException e){throw new IllegalStateException("Cannot scan pre-screen matches",e);}
        }
        /** Persists the offer-level results of a qualification run. */
        public void saveQualifications(String id,List<Qualification> rows) {
            try(var p=statement("INSERT INTO marketing_results VALUES(?,?,?,?)")) {
                int i=0;for(var r:rows){p.setString(1,id);p.setInt(2,i++);p.setString(3,r.riskId());p.setString(4,Json.write(r));p.addBatch();}p.executeBatch();
            }catch(SQLException e){throw new IllegalStateException("Cannot save marketing results",e);}
        }
        /** Lists qualification reservations and their retained history. */
        public List<Reservation> reservations(){return records("SELECT payload FROM marketing_reservations ORDER BY id",Reservation.class);}
        /** Persists a qualification reservation without discarding prior allocation history. */
        public void saveReservation(Reservation r){execute("MERGE INTO marketing_reservations KEY(id) VALUES(?,?,?,?,?)",r.id(),r.runId(),r.customerId(),r.campaignId(),Json.write(r));}
        /** Prepares a SQL statement and binds its arguments in order. */
        private PreparedStatement statement(String sql, Object... args) throws SQLException {
            PreparedStatement p = connection.prepareStatement(sql);
            for (int i=0;i<args.length;i++) p.setObject(i+1,args[i]);
            return p;
        }
        /** Executes a parameterized SQL statement and returns its update count. */
        private void execute(String sql, Object... args) {
            try (PreparedStatement p = statement(sql,args)) { p.executeUpdate(); }
            catch (SQLException e) {
                if ("23505".equals(e.getSQLState())) throw new Problem(409,"Duplicate external reference or source decision identifier");
                throw new IllegalStateException("Database write failed",e);
            }
        }
        /** Loads and deserializes a collection of stored JSON records. */
        private <T> List<T> records(String sql, Class<T> type, Object... args) {
            try (PreparedStatement p = statement(sql,args); ResultSet r = p.executeQuery()) {
                List<T> items = new ArrayList<>();
                while (r.next()) items.add(Json.read(r.getString(1),type));
                return items;
            } catch (SQLException e) { throw new IllegalStateException("Database read failed",e); }
        }
        /** Deserializes the first stored JSON result into the requested type, or returns null when absent. */
        private <T> T one(String sql, Class<T> type, Object... args) {
            List<T> rows = records(sql,type,args); return rows.isEmpty() ? null : rows.get(0);
        }
        /** Retrieves the customer record required by this workflow. */
        public Customer customer(String id) { return one("SELECT payload FROM customers WHERE id=?",Customer.class,id); }
        /** Persists the customer and associated profile and decision history. */
        public void saveCustomer(Customer c) {
            execute("MERGE INTO customers(id,external_ref,batch_id,payload) KEY(id) VALUES(?,?,?,?)",
                c.id(),c.current().data().externalReference(),c.current().batchId(),Json.write(c));
            saveContact(c);
            String token=com.lending.engine.bureau.infrastructure.BureauDatabase.hash(List.of(c.current(),c.decisions().stream().filter(d->d.profileVersion()==c.current().version()).toList()));var before=pipeline(c.id());
            if(before==null||!before.token().equals(token)){var next=new Pipeline(c.id(),token,"QUEUED",null,java.time.Instant.now().toString(),"Automatic qualification queued");execute("MERGE INTO customer_pipeline KEY(customer_id) VALUES(?,?,?,?,?)",c.id(),token,next.state(),0L,Json.write(next));}
        }
        /** Persists the synthetic customer contact details and channel preferences. */
        private void saveContact(Customer c){var old=contact(c.id());var channels=c.current().data().preferredChannels();if(channels==null)channels=old==null?List.of("EMAIL"):old.preferredChannels();if(old==null||!old.preferredChannels().equals(channels)){int suffix=Math.floorMod(c.id().hashCode(),100);var next=new Contact(c.id(),old==null?1:old.version()+1,"SYNTHETIC",c.id()+"@customers.invalid",String.format(Locale.ROOT,"+120255501%02d",suffix),"SIMULATED "+c.id().substring(0,8)+" | DEMO CITY ZZ 00000 US",List.copyOf(channels));execute("MERGE INTO customer_contacts KEY(customer_id) VALUES(?,?)",c.id(),Json.write(next));}}
        /** Creates missing synthetic contacts for existing customers during startup recovery. */
        private void backfillContacts(){while(true){var missing=records("SELECT c.payload FROM customers c WHERE NOT EXISTS(SELECT 1 FROM customer_contacts t WHERE t.customer_id=c.id) LIMIT 500",Customer.class);if(missing.isEmpty())return;missing.forEach(this::saveContact);}}
        /** Loads the customer synthetic contact details and delivery preferences. */
        public Contact contact(String id){return one("SELECT payload FROM customer_contacts WHERE customer_id=?",Contact.class,id);}
        /** Loads the latest automatic processing state for a customer. */
        public Pipeline pipeline(String id){return one("SELECT payload FROM customer_pipeline WHERE customer_id=?",Pipeline.class,id);}
        /** Returns a bounded batch of customer pipelines due for background processing. */
        public List<Pipeline> pendingPipelines(long now,int limit){return records("SELECT payload FROM customer_pipeline WHERE state IN ('QUEUED','RETRYING','BUREAU_PENDING') AND next_at<=? ORDER BY next_at,customer_id LIMIT ?",Pipeline.class,now,limit);}
        /** Persists the customer pipeline state and its next scheduled processing time. */
        public void savePipeline(Pipeline p,long nextAt){execute("UPDATE customer_pipeline SET state=?,next_at=?,payload=? WHERE customer_id=? AND token=?",p.state(),nextAt,Json.write(p),p.customerId(),p.token());}
        /** Returns a bounded page of customer summaries using the requested batch filter. */
        public Page<CustomerSummary> customers(String batchId, int offset, int limit) {
            String where = batchId == null ? "" : " WHERE batch_id=?";
            List<Object> args = new ArrayList<>(); if (batchId != null) args.add(batchId);
            long total;
            try (PreparedStatement p = statement("SELECT COUNT(*) FROM customers"+where,args.toArray()); ResultSet r = p.executeQuery()) {
                r.next(); total = r.getLong(1);
            } catch (SQLException e) { throw new IllegalStateException(e); }
            args.add(limit); args.add(offset);
            List<CustomerSummary> rows = records("SELECT payload FROM customers"+where+" ORDER BY id LIMIT ? OFFSET ?",Customer.class,args.toArray()).stream().map(c -> {
                Map<Product,Status> statuses = new EnumMap<>(Product.class);
                for (Product product : Product.values()) { Decision d = c.latest(product); if (d != null) statuses.put(product,d.status()); }
                return new CustomerSummary(c.id(),c.current().data().displayName(),c.current().version(),c.current().origin(),c.current().batchId(),statuses,c.current().data().prescreenOptOut());
            }).toList();
            return new Page<>(rows,total,offset,limit);
        }
        /** Loads the customers belonging to a synthetic batch for population evaluation. */
        public List<Customer> populationCustomers(String batchId) {
            List<Customer> rows = batchId == null ? records("SELECT payload FROM customers ORDER BY id LIMIT 50001",Customer.class)
                : records("SELECT payload FROM customers WHERE batch_id=? ORDER BY id LIMIT 50001",Customer.class,batchId);
            if (rows.size()>50000) throw new Problem(422,"Select a simulation batch; a local snapshot supports at most 50,000 customers");
            return rows;
        }
        /** Finds an imported decision by its source-system identity. */
        public Decision importedDecision(String source, String id) { return one("SELECT payload FROM imports WHERE source_system=? AND source_id=?",Decision.class,source,id); }
        /** Records the source-system decision identity used to deduplicate imports. */
        public void indexImport(Decision d) { execute("INSERT INTO imports VALUES(?,?,?)",d.sourceSystem(),d.sourceDecisionId(),Json.write(d)); }
        /** Retrieves the requested synthetic-generation job and its progress. */
        public Job job(String id) { return one("SELECT payload FROM jobs WHERE id=?",Job.class,id); }
        /** Lists recorded generation or training jobs and their outcomes. */
        public List<Job> jobs() { return records("SELECT payload FROM jobs ORDER BY id",Job.class); }
        /** Persists the generation job state, progress and outcome. */
        public void saveJob(Job job) { execute("MERGE INTO jobs KEY(id) VALUES(?,?)",job.id(),Json.write(job)); }
        /** Retrieves the specified pre-screen or simulation population. */
        public Population population(String id) { return one("SELECT payload FROM populations WHERE id=?",Population.class,id); }
        /** Lists the available population snapshots. */
        public List<Population> populations() { return records("SELECT payload FROM populations ORDER BY id",Population.class); }
        /** Persists a population snapshot together with its exact eligible members. */
        public void savePopulation(Population p, List<Member> members) {
            execute("INSERT INTO populations VALUES(?,?)",p.id(),Json.write(p));
            try (PreparedStatement s = statement("INSERT INTO members VALUES(?,?,?,?,?)")) {
                for (Member m : members) {
                    s.setString(1,p.id()); s.setString(2,m.customerId()); s.setString(3,m.product().name());
                    s.setInt(4,m.profileVersion()); s.setString(5,m.decisionId()); s.addBatch();
                }
                s.executeBatch();
            } catch (SQLException e) { throw new IllegalStateException("Cannot save population membership",e); }
        }
        /** Returns a page of members and their decision references from a population snapshot. */
        public Page<Member> members(String id, int offset, int limit) {
            Population population = population(id);
            if (population == null) throw new Problem(404,"Population not found");
            try (PreparedStatement p = statement("SELECT customer_id,profile_version,product,decision_id FROM members WHERE population_id=? ORDER BY customer_id,product LIMIT ? OFFSET ?",id,limit,offset); ResultSet r=p.executeQuery()) {
                List<Member> rows=new ArrayList<>();
                while(r.next()) rows.add(new Member(r.getString(1),r.getInt(2),Product.valueOf(r.getString(3)),r.getString(4)));
                return new Page<>(rows,population.qualifiedPairs(),offset,limit);
            } catch (SQLException e) { throw new IllegalStateException(e); }
        }
    }
}
