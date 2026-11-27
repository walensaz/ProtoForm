package net.protoform.infrastructure

import net.protoform.core.model.KubeResourceType
import net.protoform.infrastructure.kube.KubectlPortForwardAdapter
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class KubectlPortForwardAdapterSpec extends AnyFunSuite with Matchers {

  test("parseForwardedLocalPort extracts IPv4 and IPv6 bound ports") {
    KubectlPortForwardAdapter.parseForwardedLocalPort("Forwarding from 127.0.0.1:54321 -> 50051") shouldBe Some(54321)
    KubectlPortForwardAdapter.parseForwardedLocalPort("Forwarding from [::1]:61000 -> 9090") shouldBe Some(61000)
    KubectlPortForwardAdapter.parseForwardedLocalPort("Handling connection for 54321") shouldBe None
  }

  test("listContexts, listNamespaces, and listTargets parse kubectl outputs cleanly") {
    val adapter = new KubectlPortForwardAdapter(
      runCommand = args => {
        if (args.contains("get-contexts")) Right("prod-gke\ndev-kind\n")
        else if (args.contains("namespaces")) Right("default payments orders")
        else if (args.contains("services")) Right(
          """{"items":[{"metadata":{"name":"order-grpc"},"spec":{"ports":[{"port":50051}]}}]}"""
        )
        else Right("""{"items":[{"metadata":{"name":"order-pod-0"},"spec":{"containers":[{"ports":[{"containerPort":9090}]}]}}]}""")
      }
    )

    adapter.listContexts() shouldBe Right(List("prod-gke", "dev-kind"))
    adapter.listNamespaces(Some("prod-gke")) shouldBe Right(List("default", "payments", "orders"))
    val targets = adapter.listTargets(Some("prod-gke"), "orders").fold(e => fail(e.message), identity)
    targets should have size 2
    targets.head.resourceType shouldBe KubeResourceType.Service
    targets.head.name shouldBe "order-grpc"
    targets.head.ports shouldBe List(50051)
    targets(1).resourceType shouldBe KubeResourceType.Pod
    targets(1).ports shouldBe List(9090)
  }
}
