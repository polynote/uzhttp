# uzhttp

This (Micro-Z-HTTP, or "uzi-HTTP" if you like) is a minimal HTTP server using [ZIO](https://github.com/zio/zio). It has
essentially no features. You probably shouldn't use it.

## Why?

This was made to support the HTTP serving needs of [Polynote](https://github.com/polynote/polynote) – which are minimal,
because Polynote is a single-page app that only needs to serve some static files and handle websockets. As a result,
this is basically all that uzhttp supports.

## Should I use it?

Probably not! Here are just a few better options:

* If you want a full-featured HTTP server which is battle-tested, built on robust technology, supports middleware, and
  has a purely functional API with a nice DSL, go for [http4s](https://github.com/http4s/http4s) – it has pretty good
  interoperability with ZIO by using [zio-interop-cats](https://github.com/zio/interop-cats).
* If you want the above features but with a native ZIO solution, wait for [zio-http](https://github.com/zio/zio-http).
* If you want a more minimal solution, that's still got a prinicpled, purely functional API but is production-ready and
  properly engineered, take a look at [finch](https://github.com/finagle/finch).

### I'm still considering uzhttp. What are its features?

* Uses 100% non-blocking NIO for its networking (after it's bound, anyway), so it won't gobble up your blocking thread
  pool.
* Supports the basic HTTP request types as well as basic websockets.
* Has no dependencies other than zio and zio-streams (not even [zio-nio](https://github.com/zio/zio-nio), since Polynote –
  and therefore uzhttp – has to support Scala 2.11 thanks to Spark 🙄)

### What important features does it lack?

* Does not handle fancy new-fangled HTTP 1.1 things like chunked transfer encoding of requests (or responses, unless you
  build it yourself).
* Does not support SSL. Nobody really wants to deal with Java's SSL stuff, so the idea is that the app will be behind a
  reverse proxy that deals with things like SSL termination, SSO, etc.
* No fancy routing DSL whatsover. It takes a function that gets a request and returns a `ZIO[R, HTTPError, Response]`
  and that's basically it.
* There's nothing else provided for you, either. No authentication stuff built-in (you're using a proxy, remember?). No
  pluggable middleware or things like that. It won't even parse request URIs into meaningful pieces for you. It's
  request to response; anything else is yours to deal with.


## How do I use it?

To create a server, you use the `uzhttp.Server.builder` constructor. This gives you a builder, which has methods to
specify where to listen, how to respond to requests, and how to handle errors. Once you've done that, you call `serve`
which gives you a `ZManaged[R with Blocking, Throwable, Server]`. You can either `useForever` this (if you don't need
to do anything else with the server), or you can `use` it as long as you end with `awaitShutdown`:

```scala
serverM.use {
  server => log("It's alive!") *> server.awaitShutdown
}
```

The `Blocking` required for these operations is used for:
- Binding to the given port
- Selecting from NIO
- Some file operations (for generating responses with the `Response` API) which are more efficient when using blocking
  (You can avoid these if you wish, and generate your own responses).

Here's an example:

```scala
import java.net.InetSocketAddress
import zio.{App, ZIO, Task}
import uzhttp.server.{Server, Request, Response}, Response.{const, websocket}
import uzhttp.server.Websocket.Frame

object ExampleServer extends App {
  override def run(args:  List[String]): ZIO[zio.ZEnv, Nothing, Int] =
    Server.builder(new InetSocketAddress("127.0.0.1", 8080))
      .handleSome {
        case req if req.uri startsWith "/static" =>
          Response.fromResource(s"staticFiles${req.uri}", req) // deliver a static file from an application resource
        case req if req.uri == "/" =>
          Response.html("<html><body><h1>Hello world!</h1></body></html>") // deliver a constant HTML response
        case req@Request.WebsocketRequest(_, uri, _, _, inputFrames) if uri startsWith "/ws" =>
          // inputFrames are the incoming frames; construct a websocket session
          // by giving a stream of output frames
          Response.websocket(req, inputFrames.mapM(respondToWebsocketFrame))
      }.serve.useForever

  def respondToWebsocketFrame(frame: Frame): Task[Frame] = ???
}
```

## Can I make a pull request?

Absolutely! Please follow the [Polynote contributing guide](https://github.com/polynote/polynote/blob/master/CONTRIBUTING.md).
We'll gladly accept bugfixes, performance improvements, and tests; and we'd love to see features like:

* Better support of HTTP features (e.g. `Transfer-Encoding`).
* Better support of websockets (e.g. `permessage-deflate`).

uzhttp would like to stay reasonably minimal. At the time of initial commit, uzhttp was under 1k lines of code! If
you've got a great routing DSL or other conveniences, we'll gladly take a look if it's pretty small. But we might
suggest that it live as a separate library.

## License

This project is licensed under the Apache 2 license:

> Apache License
> ==============
> 
> _Version 2.0, January 2004_  
> _[http://www.apache.org/licenses/](http://www.apache.org/licenses/)_
> 
> ### Terms and Conditions for use, reproduction, and distribution
> 
> #### 1. Definitions
> 
> “License” shall mean the terms and conditions for use, reproduction, and
> distribution as defined by Sections 1 through 9 of this document.
> 
> “Licensor” shall mean the copyright owner or entity authorized by the copyright
> owner that is granting the License.
> 
> “Legal Entity” shall mean the union of the acting entity and all other entities
> that control, are controlled by, or are under common control with that entity.
> For the purposes of this definition, “control” means **(i)** the power, direct or
> indirect, to cause the direction or management of such entity, whether by
> contract or otherwise, or **(ii)** ownership of fifty percent (50%) or more of the
> outstanding shares, or **(iii)** beneficial ownership of such entity.
> 
> “You” (or “Your”) shall mean an individual or Legal Entity exercising
> permissions granted by this License.
> 
> “Source” form shall mean the preferred form for making modifications, including
> but not limited to software source code, documentation source, and configuration
> files.
> 
> “Object” form shall mean any form resulting from mechanical transformation or
> translation of a Source form, including but not limited to compiled object code,
> generated documentation, and conversions to other media types.
> 
> “Work” shall mean the work of authorship, whether in Source or Object form, made
> available under the License, as indicated by a copyright notice that is included
> in or attached to the work (an example is provided in the Appendix below).
> 
> “Derivative Works” shall mean any work, whether in Source or Object form, that
> is based on (or derived from) the Work and for which the editorial revisions,
> annotations, elaborations, or other modifications represent, as a whole, an
> original work of authorship. For the purposes of this License, Derivative Works
> shall not include works that remain separable from, or merely link (or bind by
> name) to the interfaces of, the Work and Derivative Works thereof.
> 
> “Contribution” shall mean any work of authorship, including the original version
> of the Work and any modifications or additions to that Work or Derivative Works
> thereof, that is intentionally submitted to Licensor for inclusion in the Work
> by the copyright owner or by an individual or Legal Entity authorized to submit
> on behalf of the copyright owner. For the purposes of this definition,
> “submitted” means any form of electronic, verbal, or written communication sent
> to the Licensor or its representatives, including but not limited to
> communication on electronic mailing lists, source code control systems, and
> issue tracking systems that are managed by, or on behalf of, the Licensor for
> the purpose of discussing and improving the Work, but excluding communication
> that is conspicuously marked or otherwise designated in writing by the copyright
> owner as “Not a Contribution.”
> 
> “Contributor” shall mean Licensor and any individual or Legal Entity on behalf
> of whom a Contribution has been received by Licensor and subsequently
> incorporated within the Work.
> 
> #### 2. Grant of Copyright License
> 
> Subject to the terms and conditions of this License, each Contributor hereby
> grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free,
> irrevocable copyright license to reproduce, prepare Derivative Works of,
> publicly display, publicly perform, sublicense, and distribute the Work and such
> Derivative Works in Source or Object form.
> 
> #### 3. Grant of Patent License
> 
> Subject to the terms and conditions of this License, each Contributor hereby
> grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free,
> irrevocable (except as stated in this section) patent license to make, have
> made, use, offer to sell, sell, import, and otherwise transfer the Work, where
> such license applies only to those patent claims licensable by such Contributor
> that are necessarily infringed by their Contribution(s) alone or by combination
> of their Contribution(s) with the Work to which such Contribution(s) was
> submitted. If You institute patent litigation against any entity (including a
> cross-claim or counterclaim in a lawsuit) alleging that the Work or a
> Contribution incorporated within the Work constitutes direct or contributory
> patent infringement, then any patent licenses granted to You under this License
> for that Work shall terminate as of the date such litigation is filed.
> 
> #### 4. Redistribution
> 
> You may reproduce and distribute copies of the Work or Derivative Works thereof
> in any medium, with or without modifications, and in Source or Object form,
> provided that You meet the following conditions:
> 
> * **(a)** You must give any other recipients of the Work or Derivative Works a copy of
> this License; and
> * **(b)** You must cause any modified files to carry prominent notices stating that You
> changed the files; and
> * **(c)** You must retain, in the Source form of any Derivative Works that You distribute,
> all copyright, patent, trademark, and attribution notices from the Source form
> of the Work, excluding those notices that do not pertain to any part of the
> Derivative Works; and
> * **(d)** If the Work includes a “NOTICE” text file as part of its distribution, then any
> Derivative Works that You distribute must include a readable copy of the
> attribution notices contained within such NOTICE file, excluding those notices
> that do not pertain to any part of the Derivative Works, in at least one of the
> following places: within a NOTICE text file distributed as part of the
> Derivative Works; within the Source form or documentation, if provided along
> with the Derivative Works; or, within a display generated by the Derivative
> Works, if and wherever such third-party notices normally appear. The contents of
> the NOTICE file are for informational purposes only and do not modify the
> License. You may add Your own attribution notices within Derivative Works that
> You distribute, alongside or as an addendum to the NOTICE text from the Work,
> provided that such additional attribution notices cannot be construed as
> modifying the License.
> 
> You may add Your own copyright statement to Your modifications and may provide
> additional or different license terms and conditions for use, reproduction, or
> distribution of Your modifications, or for any such Derivative Works as a whole,
> provided Your use, reproduction, and distribution of the Work otherwise complies
> with the conditions stated in this License.
> 
> #### 5. Submission of Contributions
> 
> Unless You explicitly state otherwise, any Contribution intentionally submitted
> for inclusion in the Work by You to the Licensor shall be under the terms and
> conditions of this License, without any additional terms or conditions.
> Notwithstanding the above, nothing herein shall supersede or modify the terms of
> any separate license agreement you may have executed with Licensor regarding
> such Contributions.
> 
> #### 6. Trademarks
> 
> This License does not grant permission to use the trade names, trademarks,
> service marks, or product names of the Licensor, except as required for
> reasonable and customary use in describing the origin of the Work and
> reproducing the content of the NOTICE file.
> 
> #### 7. Disclaimer of Warranty
> 
> Unless required by applicable law or agreed to in writing, Licensor provides the
> Work (and each Contributor provides its Contributions) on an “AS IS” BASIS,
> WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
> including, without limitation, any warranties or conditions of TITLE,
> NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE. You are
> solely responsible for determining the appropriateness of using or
> redistributing the Work and assume any risks associated with Your exercise of
> permissions under this License.
> 
> #### 8. Limitation of Liability
> 
> In no event and under no legal theory, whether in tort (including negligence),
> contract, or otherwise, unless required by applicable law (such as deliberate
> and grossly negligent acts) or agreed to in writing, shall any Contributor be
> liable to You for damages, including any direct, indirect, special, incidental,
> or consequential damages of any character arising as a result of this License or
> out of the use or inability to use the Work (including but not limited to
> damages for loss of goodwill, work stoppage, computer failure or malfunction, or
> any and all other commercial damages or losses), even if such Contributor has
> been advised of the possibility of such damages.
> 
> #### 9. Accepting Warranty or Additional Liability
> 
> While redistributing the Work or Derivative Works thereof, You may choose to
> offer, and charge a fee for, acceptance of support, warranty, indemnity, or
> other liability obligations and/or rights consistent with this License. However,
> in accepting such obligations, You may act only on Your own behalf and on Your
> sole responsibility, not on behalf of any other Contributor, and only if You
> agree to indemnify, defend, and hold each Contributor harmless for any liability
> incurred by, or claims asserted against, such Contributor by reason of your
> accepting any such warranty or additional liability.
> 
> _END OF TERMS AND CONDITIONS_
> 
> ### APPENDIX: How to apply the Apache License to your work
> 
> To apply the Apache License to your work, attach the following boilerplate
> notice, with the fields enclosed by brackets `[]` replaced with your own
> identifying information. (Don't include the brackets!) The text should be
> enclosed in the appropriate comment syntax for the file format. We also
> recommend that a file or class name and description of purpose be included on
> the same “printed page” as the copyright notice for easier identification within
> third-party archives.
> 
>     Copyright 2020 uzhttp contributors
>     
>     Licensed under the Apache License, Version 2.0 (the "License");
>     you may not use this file except in compliance with the License.
>     You may obtain a copy of the License at
>     
>       http://www.apache.org/licenses/LICENSE-2.0
>     
>     Unless required by applicable law or agreed to in writing, software
>     distributed under the License is distributed on an "AS IS" BASIS,
>     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
>     See the License for the specific language governing permissions and
>     limitations under the License.
> 
