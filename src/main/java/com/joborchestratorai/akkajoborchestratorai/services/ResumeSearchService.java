package com.joborchestratorai.akkajoborchestratorai.services;

import akka.actor.typed.ActorRef;

import akka.actor.typed.ActorSystem;

import akka.actor.typed.javadsl.AskPattern;

import com.joborchestratorai.akkajoborchestratorai.actors.MasterActor;

import com.joborchestratorai.akkajoborchestratorai.models.SearchResult;

import org.springframework.context.annotation.Profile;

import org.springframework.stereotype.Service;

import java.time.Duration;

import java.util.List;

import java.util.concurrent.*;

@Service

@Profile("single-node")

public class ResumeSearchService {

    private final ActorSystem<MasterActor.Command> actorSystem;

    private final Duration askTimeout = Duration.ofSeconds(30);

    public ResumeSearchService(ActorSystem<MasterActor.Command> actorSystem) {

        this.actorSystem = actorSystem;

    }

    // Using tell pattern (fire and forget)

    public void indexExcelFile(String filePath) {

        actorSystem.tell(new MasterActor.ProcessExcelFile(filePath));

    }

    // Using ask pattern (request-response)

    public CompletionStage<List<SearchResult>> searchResumes(String jobDescription, int topK) {

        return AskPattern.ask(

                actorSystem,

                (ActorRef<List<SearchResult>> replyTo) ->

                        new MasterActor.ProcessJobDescription(jobDescription, topK, replyTo),

                askTimeout,

                actorSystem.scheduler()

        );

    }

}