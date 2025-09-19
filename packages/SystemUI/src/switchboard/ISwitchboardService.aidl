package switchboard;

import switchboard.ISwitchboardAgentCallback;
import switchboard.ISwitchboardOutlookCallback;

/** Switchboard service interface. */
interface ISwitchboardService {
    /** Gather context from the user */
    oneway void gatherContext(ISwitchboardOutlookCallback callback);
    /** Send a span click event to the service */
    oneway void spanClick(String clickEvent, ISwitchboardOutlookCallback callback);
    /** Perform an action given a string description */
    boolean takeAction(String packageName, String actionDescription);
    /** Query the search agent with a question */
    oneway void querySearchAgent(String query, ISwitchboardAgentCallback callback);
    /** Query the search agent with a question */
    oneway void followupSearchAgent(String query, int conversationId, ISwitchboardAgentCallback callback);
    /** Route a query to the appropriate search provider (returns: wafer_search, web_search, app_store_search, or perplexity_search) */
    String routeQuery(String query);
    /** Ingest a notification for indexing */
    oneway void ingestNotification(String packageName, long timestamp_ms, String title, String text, String bigText, String contentIntent);
    /** Launch a source by its ID (via intent or deep link) */
    boolean launchSource(long sourceId);
}
