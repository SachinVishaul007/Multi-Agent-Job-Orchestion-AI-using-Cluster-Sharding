package com.joborchestratorai.akkajoborchestratorai.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.PostStop;
import akka.actor.typed.javadsl.*;
import com.joborchestratorai.akkajoborchestratorai.models.ResumeData;
import com.joborchestratorai.akkajoborchestratorai.models.SearchResult;

import java.util.List;

public class MasterActor extends AbstractBehavior<MasterActor.Command> {

    public interface Command {}

    // Messages
    public static class ProcessExcelFile implements Command {
        public final String filePath;
        public ProcessExcelFile(String filePath) { this.filePath = filePath; }
    }

    public static class ProcessJobDescription implements Command {
        public final String jobDescription;
        public final int topK;
        public final ActorRef<List<SearchResult>> replyTo;

        public ProcessJobDescription(String jobDescription, int topK, ActorRef<List<SearchResult>> replyTo) {
            this.jobDescription = jobDescription;
            this.topK = topK;
            this.replyTo = replyTo;
        }
    }

    public static class StoreResumeData implements Command {
        public final ResumeData resumeData;
        public StoreResumeData(ResumeData resumeData) { this.resumeData = resumeData; }
    }

    public static class SearchWithOpenAI {
        public final String jobDescription;
        public final int limit;
        public final ActorRef<SearchComplete> replyTo;

        public SearchWithOpenAI(String jobDescription, int limit, ActorRef<SearchComplete> replyTo) {
            this.jobDescription = jobDescription;
            this.limit = limit;
            this.replyTo = replyTo;
        }
    }

    public static class SearchComplete {
        public final List<SearchResult> results;
        public SearchComplete(List<SearchResult> results) { this.results = results; }
    }

    public static class AdaptedSearchComplete implements Command {
        public final SearchComplete searchComplete;
        public final ActorRef<List<SearchResult>> originalReplyTo;

        public AdaptedSearchComplete(SearchComplete searchComplete, ActorRef<List<SearchResult>> originalReplyTo) {
            this.searchComplete = searchComplete;
            this.originalReplyTo = originalReplyTo;
        }
    }

    private final ActorRef<Object> storageActor;
    private final ActorRef<Object> openAIActor;
    private final ActorRef<ProcessExcelFile> fileReaderActor;

    public static Behavior<Command> create() {
        return Behaviors.setup(MasterActor::new);
    }

    private MasterActor(ActorContext<Command> context) {
        super(context);
        this.storageActor = context.spawn(StorageActor.create(), "storage-actor");
        this.openAIActor = context.spawn(OpenAIActor.create(), "openai-actor");
        this.fileReaderActor = context.spawn(FileReaderActor.create(storageActor), "file-reader-actor");
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(ProcessExcelFile.class, msg -> {
                    fileReaderActor.tell(msg);
                    return this;
                })
                .onMessage(ProcessJobDescription.class, this::onProcessJobDescription)
                .onMessage(AdaptedSearchComplete.class, this::onSearchComplete)
                .onSignal(PostStop.class, signal -> onPostStop())
                .build();
    }

    private Behavior<Command> onProcessJobDescription(ProcessJobDescription msg) {
        // Create a message adapter to convert the response
        ActorRef<SearchComplete> responseAdapter = getContext().messageAdapter(
                SearchComplete.class,
                searchComplete -> new AdaptedSearchComplete(searchComplete, msg.replyTo)
        );

        // Forward the request to openAIActor with the adapter
        openAIActor.tell(new SearchWithOpenAI(msg.jobDescription, msg.topK, responseAdapter));
        return this;
    }

    private Behavior<Command> onSearchComplete(AdaptedSearchComplete msg) {
        // Reply to the original sender with the search results
        msg.originalReplyTo.tell(msg.searchComplete.results);
        return this;
    }

    private Behavior<Command> onPostStop() {
        return this;
    }
}