package com.joborchestratorai.akkajoborchestratorai.config;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.SpawnProtocol;
import akka.actor.typed.javadsl.Behaviors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AkkaConfig {
    
    @Bean
    public ActorSystem<SpawnProtocol.Command> actorSystem() {
        return ActorSystem.create(Behaviors.setup(context -> SpawnProtocol.create()),
            "akka-job-orchestrator-system");
    }
}
