/*
 * Copyright (c) 2019. Salduba Technologies LLC, all right reserved
 */

package com.saldubatech.test.utils

import akka.actor.ActorSystem

import scala.languageFeature.postfixOps


class SampleWordSpecLike extends BaseActorSpec(ActorSystem("SampleWordSpecLike")) {

		"An Induct" when {
			// Initialize the environment
			"it is created" should {
				"install the received ExecutorProxy in a message as  its executor" in {

				}
				"call the OnCompleteStaging Method when it receives a Complete Staging message wiht a mission" in {

				}
				"remove the resource assignements for a job and remove it from the pending jobs when it receives a 'CompleteProcessing' Message" in {

				} ensuring true
				"invoke processNext method" in {
					5 shouldBe 5
				}
			}
		}
	it when {
		"receiving blah" should {
			"do something smart" in {

			}
		}
	}
	"This other induct" when {
		"enforcing tight security" must {
			"reject all calls" in {

			}

		}
	}
}
