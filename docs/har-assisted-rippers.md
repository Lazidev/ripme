# HAR-assisted ripper authoring

**Status:** draft, for discussion. Nothing implemented.

## The idea

Add a configuration tab where a user drops in a HAR file recorded from a site RipMe
doesn't support, and RipMe helps produce a working ripper from it.

## Why it's appealing

New rippers are the third priority in `CONTRIBUTING.md`, but each one currently needs a
Java developer who knows the `AbstractHTMLRipper` contract. Most of the raw material for a
ripper genuinely is in a browser recording, and users who want a site supported can
usually produce a HAR far more easily than a pull request.

## What a HAR actually gives you

A HAR is a reliable source for:

* The media host and URL shape, e.g. `cdn.<site>.com/content/<id>/<name>.jpg`.
* Separating media from tracker and ad noise, using MIME type, host and response size.
* API endpoints and their JSON response shape, when the page loads content over XHR.
* Required request headers: referer, cookies, user agent.
* Whether content is server-rendered or fetched later, from request ordering.

If the HAR was saved with response bodies, it also contains the HTML, which is what you
need to infer selectors. `CONTRIBUTING.md` already observes that browsing the HTML is
usually the most important part of writing a ripper.

## What a HAR does not give you

This is the crux, so it's worth being concrete. The `EliteBabesRipper` (NSFW) was written
from two HARs of that site. The HARs were genuinely useful, but roughly the following came
from elsewhere — live probing of pages the recording didn't include, plus judgement:

* **Resolution policy.** The ripper picks the largest entry from each `srcset`. A HAR
  records the browser doing the *opposite*: fetching the small rendition it decided it
  needed. A generator that faithfully reproduces observed traffic downloads thumbnails
  forever, while appearing to work.
* **Pagination.** The listing's `apiUrl` and `totalPages` live in an inline `<script>`. The
  `gridapi` request only appears in a HAR if the user scrolled far enough while recording.
  Going from one observed request with `&mpage=2` to "loop until the page count, and stop
  early when a page yields nothing new" is inference, not extraction.
* **Selector choice.** Listing thumbnails are wrapped in two links, and the ripper
  deliberately prefers `div.img-overlay p a[href]` over the more obvious `a[href]`, because
  on *video* posts the image links out to the partner site hosting the video. Neither HAR
  contained a video post.
* **Filename collisions.** The CDN numbers files per set, not globally, so `0001-01_1200.jpg`
  recurs across sets. Discovering this needed several *different* galleries fetched and
  compared — data that is nowhere in a HAR of one listing. This was not hypothetical: a
  real rip silently lost images to it before the ripper qualified filenames by set.
* **Product decisions.** Whether a listing expands into the queue or rips as one job, how
  the folder is named, and how names are trimmed for the Windows path limit. These were
  settled by asking the user, and the answer changed once.

The failure mode matters more than the hit rate. A generated ripper that compiles, runs,
reports success, and quietly collects thumbnails while overwriting half of them is worse
than no feature, because it looks like it worked.

## Why not generate Java

Three separate obstacles, none fatal alone, but together they argue for a different shape:

1. **Ripper discovery is compile-time bound.** `Utils.getClassesForPackage` enumerates a
   filesystem directory or entries inside the running jar. A generated ripper would need an
   external scan path and a `URLClassLoader`, in a method that is already brittle.
2. **Compiling needs a JDK.** `ToolProvider.getSystemJavaCompiler()` returns `null` on a
   plain JRE, so this would silently not work for some users.
3. **It compiles and executes code derived from a website's own content.** That is an
   unpleasant attack surface for a tool whose whole job is visiting untrusted sites.

## Proposed shape

Generate a **declarative site spec**, not Java, and interpret it at runtime.

* A single `ConfigurableRipper extends AbstractHTMLRipper` that reads site specs from files
  in the config directory. This needs **no change to ripper discovery** — it is just one
  more compiled ripper, whose `canRip` consults the loaded specs.
* The HAR tab performs *analysis* and pre-fills a spec: media host and URL pattern,
  candidate selectors, detected API endpoints, required headers.
* A **live preview** shows the URLs the current spec would download from a given page, with
  their resolutions, so the user can adjust a selector and immediately re-check. This is
  the load-bearing part: it turns "did the generator guess right?" into a two-second check
  rather than an unanswerable question.
* An **export as Java scaffold** escape hatch, for sites the spec language can't express.

A secondary benefit may outweigh the generation itself: site definitions become small
shareable text files, so when a site changes its markup a user can fix it without waiting
for a release.

### Spec sketch

Expressing the existing elitebabes ripper (NSFW) as data, to test whether the format can
carry a real ripper:

```json
{
  "domain": "elitebabes.com",
  "gallery": { "pathSegments": 1 },
  "listing": {
    "pathPrefixes": ["model", "model-tag", "collections", "tag", "category"],
    "itemSelector": "li > figure",
    "itemLink": ["div.img-overlay p a[href]", "a[href]"],
    "pagination": {
      "type": "inlineScriptApi",
      "apiUrlPattern": "apiUrl\\s*=\\s*'([^']*)'",
      "totalPagesPattern": "totalPages\\s*=\\s*parseInt\\('(\\d+)'\\)",
      "pageParam": "mpage",
      "maxPages": 1000
    }
  },
  "media": [
    { "selector": "a[data-fancybox]", "srcset": "data-srcset", "pick": "largestWidth",
      "fallbackAttr": "href" },
    { "selector": "video > source[src]", "attr": "src", "stripQuery": true, "first": true }
  ],
  "output": {
    "folder": "subjectOnly",
    "flattenListings": true,
    "qualifyFileNamesWith": "gallerySlug"
  },
  "requestDelayMs": 500
}
```

Notes on the parts that resist being data:

* `itemLink` as an ordered fallback list captures the video-post case, but only because
  someone already knew about it. Nothing in a HAR would populate that second entry.
* Skipping a gallery that yields no media, rather than aborting the rip, is behaviour rather
  than configuration. It probably belongs in `ConfigurableRipper` unconditionally.
* Filename shortening (trim the slug, append a hash of the full slug so two long names
  can't collapse) is also behaviour, not a knob.

That the awkward parts are all *behaviour* is mildly encouraging: it suggests the spec can
stay small if `ConfigurableRipper` is opinionated.

## Suggested phasing

Each phase is independently useful, and each can be abandoned without wasting the last.

1. `ConfigurableRipper` plus the spec format, with hand-written specs. Useful on its own:
   simple sites stop needing Java at all. Validate by expressing two or three existing
   rippers as specs and checking they behave identically.
2. The HAR analyzer and the preview panel, pre-filling specs and showing what a spec would
   download.
3. Optional: an LLM pass that proposes selectors and spots pagination scripts. This is a
   good fit for the fuzzy inference, but it should propose *into the spec and preview loop*,
   never emit Java that gets compiled and run. Needs an API key, network and cost, so it
   must be strictly opt-in.

## Risks and open questions

* **`canRip` ordering.** `AbstractRipper.getRipper` returns the first ripper whose `canRip`
  passes, and `getClassesForPackage` order is effectively arbitrary. `ConfigurableRipper`
  must be consulted only after built-ins fail, or a spec could shadow a real ripper.
* **HARs contain secrets.** Session cookies and auth headers are routinely present.
  Anything that imports, stores or shares a HAR or a derived spec must scrub these
  deliberately, and the UI should say so.
* **Spec versioning.** Shared specs will outlive format changes; specs need a version field
  from day one.
* **How much of the spec language is enough?** Worth answering empirically in phase 1
  before building any generation, by counting how many existing rippers could be expressed.
* **Scope.** This is a sizeable feature for a project whose `CONTRIBUTING.md` notes
  contributors have limited volunteer time. Phase 1 alone may be the right stopping point.
