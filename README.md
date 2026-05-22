# CraftEngine Host Extension

### Disclaimer
> [!CAUTION]
> This project is intended for educational purposes. Please do not misuse the example hosting service and strictly adhere to the service provider’s terms of service. If you need to distribute resource packages on a large scale, please use [S3 Host](https://xiao-momi.github.io/craft-engine-wiki/getting_start/set_up_host/s3). Users assume full responsibility for any issues that arise during use (including those resulting from misuse). The developer of this project hereby declares: We assume no responsibility for any consequences arising from misuse, improper configuration, or violation of platform terms. Users must ensure that their usage complies with all applicable laws, regulations, and third-party service agreements.
> 
> 本项目为学习目的，请勿滥用示例的托管方式服务，严格遵守服务提供方的服务条款。如需大量分发资源包，请使用[S3托管](https://ce.gtemc.cn/zh-Hans/getting_start/set_up_host/s3)。使用过程中出现的任何问题（包括因滥用导致的问题）均由使用者自行承担全部责任。本项目开发者特此声明：对于因滥用、不当配置或违反平台条款所引发的任何后果概不负责。使用者应确保其使用行为符合所有适用法律法规及第三方服务协议的要求。

### gitee example

```yml
type: "gtemc:gitee"
owner: "your_owner_name"
repo: "your_repo_name" # https://gitee.com/projects/new
token: "your_token" # https://gitee.com/profile/personal_access_tokens/new
path: "resourcepacks/resource_pack.zip"
use_environment_variables: false
```
> [!TIP]
> Available environment variables: CE_GITEE_TOKEN

### github example

```yml
type: "gtemc:github"
owner: "your_owner_name"
repo: "your_repo_name" # https://github.com/new
token: "your_token" # https://github.com/settings/tokens/new
branch: "main"
path: "resourcepacks/resource_pack.zip"
use_environment_variables: false
```
> [!TIP]
> Available environment variables: CE_GITHUB_TOKEN

### polymath example

```yml
type: "gtemc:polymath"
server-url: "https://your_server_url/upload"
secret: "your_secret"
use_environment_variables: false
```
> [!TIP]
> Available environment variables: CE_POLYMATH_SECRET

### edgeone pages example

```yml
type: "gtemc:edgeone_pages"
is_international: false # If you are using the international version, please enable this option
# endpoint: "https://your_endpoint/v1" # Optional
api_token: "your_api_token" # https://pages.edgeone.ai/document/api-token / https://cloud.tencent.com/document/product/1552/127422 
project_id: "pages-xxxx" # On the project overview page, in the address bar: .../pages/project/pages-xxxx/... (where "pages-xxxx" is the specific page ID)
use_environment_variables: false
```
> [!TIP]
> Available environment variables: CE_EDGEONE_PAGES_API_TOKEN
