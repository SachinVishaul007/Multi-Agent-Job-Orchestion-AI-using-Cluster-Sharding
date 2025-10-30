package com.joborchestratorai.akkajoborchestratorai.config;

import akka.actor.typed.ActorSystem;
import akka.management.cluster.bootstrap.ClusterBootstrap;
import akka.management.javadsl.AkkaManagement;
import com.joborchestratorai.akkajoborchestratorai.actors.ClusteredMasterActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

@Configuration
@Profile({"node1", "node2", "node3", "node4", "clustered"})  // Added node3 and node4
public class ClusteredAkkaConfig {

    @Value("${akka.remote.artery.canonical.port:2551}")
    private int akkaPort;

    @Value("${akka.management.http.port:8558}")
    private int managementPort;

    @Bean
    public Config clusteredAkkaConfiguration() {
        return ConfigFactory.parseString(String.format("""
            akka {
              actor {
                provider = cluster
                
                serializers {
                  java = "akka.serialization.JavaSerializer"
                }
                
                serialization-bindings {
                  "com.joborchestratorai.akkajoborchestratorai.actors.ResumeShardActor$Command" = java
                  "com.joborchestratorai.akkajoborchestratorai.actors.ClusteredMasterActor$Command" = java
                  "java.io.Serializable" = java
                }
              }
              
              remote.artery {
                canonical.hostname = "127.0.0.1"
                canonical.port = %d
              }
              
              cluster {
                seed-nodes = [
                  "akka://resume-search-system@127.0.0.1:2551",
                  "akka://resume-search-system@127.0.0.1:2552",
                  "akka://resume-search-system@127.0.0.1:2553",
                  "akka://resume-search-system@127.0.0.1:2554"
                ]
                downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
                split-brain-resolver {
                  active-strategy = "keep-oldest"
                  stable-after = 20s
                  keep-majority {
                    role = ""
                  }
                }
                min-nr-of-members = 2  // Require at least 2 nodes for better fault tolerance
              }
              
              management {
                http {
                  hostname = "127.0.0.1"
                  port = %d
                }
              }
            }
            """, akkaPort, managementPort));
    }

    @Bean(destroyMethod = "terminate")
    public ActorSystem<ClusteredMasterActor.Command> clusteredActorSystem() {
        ActorSystem<ClusteredMasterActor.Command> system = ActorSystem.create(
                ClusteredMasterActor.create(),
                "resume-search-system",
                clusteredAkkaConfiguration()
        );

        // Start Akka Management
        AkkaManagement.get(system).start();

        // Start Cluster Bootstrap
        ClusterBootstrap.get(system).start();

        return system;
    }
}