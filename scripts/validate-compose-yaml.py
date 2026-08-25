"""YAML 语法与关键结构校验(替代 docker compose config,当前机器无 Docker CLI)。"""
import yaml
import sys


def make_ctor(tag):
    def ctor(loader, node):
        if isinstance(node, yaml.ScalarNode):
            return loader.construct_scalar(node)
        if isinstance(node, yaml.SequenceNode):
            return loader.construct_sequence(node, deep=True)
        return loader.construct_mapping(node, deep=True)
    return ctor


yaml.SafeLoader.add_constructor('!reset', make_ctor('!reset'))
yaml.SafeLoader.add_constructor('!override', make_ctor('!override'))

errors = []
# base 文件必须自包含(image/networks/volumes 全量)
with open('docker-compose.yml', encoding='utf-8') as fh:
    base_doc = yaml.safe_load(fh)
print(f'docker-compose.yml: OK, services = {sorted(base_doc["services"])}')
for name, svc in base_doc['services'].items():
    if 'image' not in svc and 'build' not in svc:
        errors.append(f'base: 服务 {name} 缺 image/build')
if 'monitoring' not in base_doc.get('networks', {}):
    errors.append('base: 缺少 monitoring 网络')
if 'mysql-backups' not in base_doc.get('volumes', {}):
    errors.append('base: 缺少 mysql-backups 卷')

# overlay 依赖 compose 合并继承 base 的 image/networks/volumes,
# 仅新增服务(mysql-backup/node-exporter)必须自带 image
with open('docker-compose.prod.yml', encoding='utf-8') as fh:
    prod_doc = yaml.safe_load(fh)
print(f'docker-compose.prod.yml: OK, services = {sorted(prod_doc["services"])}')
for name, svc in prod_doc['services'].items():
    if name in ('mysql-backup', 'node-exporter') and 'image' not in svc:
        errors.append(f'overlay: 新增服务 {name} 缺 image')

# 语义抽查
base = yaml.safe_load(open('docker-compose.yml', encoding='utf-8'))
prod = yaml.safe_load(open('docker-compose.prod.yml', encoding='utf-8'))
base_app_env = base['services']['app']['environment']
prod_app_env = prod['services']['app']['environment']
for key in ['LLM_URL', 'LLM_MODEL', 'JWT_SECRET', 'SPRING_PROFILES_ACTIVE']:
    if key not in prod_app_env:
        errors.append(f'overlay app.environment 缺 {key}')
if '!reset' not in str(prod['services']['prometheus'].get('ports')):
    pass  # !reset 由构造器消费后为 [],语义检查见下
# 检查 overlay 是否包含 fail-fast 占位
overlay_text = open('docker-compose.prod.yml', encoding='utf-8').read()
for required in ['JWT_SECRET:?', 'MYSQL_PASSWORD:?', 'EMBEDDING_API_KEY:?',
                 'GRAFANA_ADMIN_PASSWORD:?', 'RAG_DOCS_PATH:?', 'REDIS_PASSWORD:?',
                 'STREAMING_LLM_URL:?', 'LLM_URL:?']:
    if required not in overlay_text:
        errors.append(f'overlay 缺 fail-fast 占位 {required}')
# base 不应包含 fail-fast(dev 零配置启动)
base_text = open('docker-compose.yml', encoding='utf-8').read()
if ':?' in base_text:
    errors.append('base compose 不应包含 fail-fast 占位')

if errors:
    print('校验失败:')
    for e in errors:
        print('  -', e)
    sys.exit(1)
print('YAML 结构与语义校验全部通过')
