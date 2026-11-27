import { startPage, can } from '/js/layout.js';
import { api } from '/js/api.js';
import { confirmDialog, showFieldErrors, clearFieldErrors } from '/js/ui.js';

const dictionaries = {
    'airlines': {
        title: 'Авиакомпании',
        fields: [['iataCode', 'Код IATA', 2], ['name', 'Название', 100]]
    },
    'airports': {
        title: 'Аэропорты',
        fields: [['iataCode', 'Код IATA', 3], ['name', 'Название', 100], ['city', 'Город', 60]]
    },
    'stands': {
        title: 'Стоянки',
        fields: [['airportId', 'Аэропорт', 'airports'], ['code', 'Код', 10]]
    },
    'aircraft-types': {
        title: 'Типы ВС',
        fields: [['code', 'Код', 10], ['name', 'Название', 60]]
    },
    'aircraft': {
        title: 'Воздушные суда',
        fields: [
            ['tailNumber', 'Бортовой номер', 10],
            ['aircraftTypeId', 'Тип ВС', 'aircraft-types'],
            ['airlineId', 'Авиакомпания', 'airlines']
        ]
    },
    'operations': {
        title: 'Наземные операции',
        fields: [['code', 'Код', 20], ['name', 'Название', 100]]
    },
    'operation-norms': {
        title: 'Нормативы операций',
        fields: [
            ['aircraftTypeId', 'Тип ВС', 'aircraft-types'],
            ['operationId', 'Операция', 'operations'],
            ['seqNo', '№', 'number'],
            ['durationMin', 'Мин', 'number'],
            ['isMandatory', 'Обязательная', 'boolean'],
            ['prevNormIds', 'Начинать после', 'multiple']
        ]
    },
    'delay-codes': {
        title: 'Коды задержек',
        fields: [['code', 'Код', 4], ['name', 'Название', 150]]
    }
};

let currentDictionary = 'operation-norms';
let currentPage = 1;
let selectedRecord = null;
let editingRecord = null;
let rows = [];
let normRows = [];
let currentUser = null;
let saving = false;
let searchTimer;

let loadVersion = 0;
let editorVersion = 0;
let predecessorsVersion = 0;
let pageSize = 25;
let sortField = '';
let sortDirection = 'asc';

function showError(container, error) {
    const messages = {
        VALIDATION_ERROR: 'Проверьте формат, длину текста и числовые значения.',
        DUPLICATE_CODE: 'Такая запись уже существует. Укажите другой код.',
        INACTIVE_REFERENCE: 'Связанная запись отсутствует или неактивна. Выберите другую.',
        IMMUTABLE_FIELD: 'Запись уже используется в оборотах. Её код и основные связи менять нельзя.',
        DEPENDENCY_CYCLE: 'Эта зависимость создаёт круг. Выберите другие предшествующие операции.',
        NOT_FOUND: 'Запись не найдена. Обновите таблицу.',
        UNKNOWN_DICTIONARY: 'Справочник не найден.',
        FORBIDDEN: 'У вас нет прав на это действие.',
        INTERNAL_ERROR: 'Не удалось выполнить действие. Попробуйте позже.'
    };
    container.querySelectorAll('.ref-error').forEach(box => box.remove());
    const box = document.createElement('div');
    box.className = 'alert alert-error ref-error';
    box.setAttribute('role', 'alert');
    if (currentDictionary === 'operations' && error.code === 'DUPLICATE_CODE') {
        box.textContent = 'Активная операция с таким кодом уже существует. Измените код или сначала деактивируйте другую операцию.';
    } else {
        box.textContent = messages[error.code] || 'Не удалось связаться с сервером или выполнить действие.';
    }
    container.prepend(box);
    console.error('Ошибка запроса', error.code, error.requestId, error.message);
}

function renderPager(total) {
    const container = element('pager');
    container.replaceChildren();
    const pages = Math.max(1, Math.ceil(total / pageSize));
    const info = document.createElement('span');
    const first = total === 0 ? 0 : (currentPage - 1) * pageSize + 1;
    info.textContent = first + '–' + Math.min(currentPage * pageSize, total)
        + ' из ' + total + '. Страница ' + currentPage + ' из ' + pages;
    container.append(info);
    for (const [text, page, disabled] of [
        ['Назад', currentPage - 1, currentPage === 1],
        ['Вперёд', currentPage + 1, currentPage >= pages]
    ]) {
        const button = document.createElement('button');
        button.className = 'btn';
        button.type = 'button';
        button.textContent = text;
        button.disabled = disabled;
        button.addEventListener('click', function () { currentPage = page; loadRecords(); });
        container.append(button);
    }
}

function element(id) {
    return document.getElementById(id);
}

async function loadAll(dictionary, params = {}) {
    const items = [];

    for (let page = 1; ; page++) {
        const data = await api.get('/ref/' + dictionary, {
            ...params, page: page, size: 100
        });
        items.push(...data.items);

        if (data.items.length === 0 || items.length >= data.total) {
            return items;
        }
    }
}

function recordLabel(record) {
    const code = record.iataCode || record.code || record.tailNumber || '';
    if (code && record.name) return code + ' - ' + record.name;
    return code || record.name || '';
}

function updateButtons() {
    element('add').disabled = !currentUser || !can(currentUser, 'ref.write');
    element('edit').disabled = !selectedRecord || !can(currentUser, 'ref.edit');
    element('deactivate').disabled = !selectedRecord
        || !selectedRecord.isActive || !can(currentUser, 'ref.edit');
}

function selectRecord(id) {
    selectedRecord = null;

    for (const record of rows) {
        if (record.id === id) selectedRecord = record;
    }

    for (const row of element('table').querySelectorAll('tbody tr[data-id]')) {
        const selected = Number(row.dataset.id) === id;
        row.classList.toggle('selected', selected);
        row.setAttribute('aria-selected', String(selected));
    }
    updateButtons();
}

function cellValue(record, field) {
    if (field === 'prevNormIds') {
        const numbers = [];
        for (const id of record.prevNormIds) {
            const predecessor = normRows.find(function (norm) { return norm.id === id; });
            numbers.push(predecessor ? predecessor.seqNo : '#' + id);
        }
        return numbers.join(', ') || 'постановки ВС';
    }
    if (field === 'airportId') return record.airport;
    if (field === 'aircraftTypeId') return record.aircraftType;
    if (field === 'airlineId') return record.airline;
    if (typeof record[field] === 'boolean') return record[field] ? 'да' : 'нет';
    if (record[field] == null) return '';

    return record[field];
}

function renderTable() {
    let columns = dictionaries[currentDictionary].fields.slice();
    if (currentDictionary === 'operation-norms') {
        columns = [
            ['seqNo', '№'], ['operationName', 'Операция'], ['durationMin', 'Мин'],
            ['isMandatory', 'Обязательная'], ['prevNormIds', 'Начинать после']
        ];
        if (!element('aircraft-type').value) columns.unshift(['aircraftType', 'Тип ВС']);
    }
    columns.push(['isActive', 'Активна']);

    const table = element('table');
    table.replaceChildren();
    const header = table.createTHead().insertRow();

    for (const column of columns) {
        const cell = document.createElement('th');
        cell.scope = 'col';

        const field = column[0];
        const names = { airportId: 'airport', aircraftTypeId: 'aircraftType', airlineId: 'airline' };
        const sort = names[field] || field;
        cell.textContent = column[1];

        if (field !== 'prevNormIds') {
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = column[1] + (sortField === sort ? (sortDirection === 'asc' ? ' ↑' : ' ↓') : ' ↕');
            cell.setAttribute('aria-sort', sortField !== sort ? 'none' : sortDirection === 'asc' ? 'ascending' : 'descending');
            button.addEventListener('click', function () {
                sortDirection = sortField === sort && sortDirection === 'asc' ? 'desc' : 'asc';
                sortField = sort;
                resetPage();
            });
            cell.replaceChildren(button);
        }

        header.append(cell);
    }

    const body = table.createTBody();
    for (const record of rows) {
        const row = body.insertRow();
        row.dataset.id = record.id;
        row.tabIndex = 0;
        row.setAttribute('aria-selected', 'false');
        for (const column of columns) {
            row.insertCell().textContent = cellValue(record, column[0]);
        }
    }
    if (rows.length === 0) {
        const cell = body.insertRow().insertCell();
        cell.colSpan = columns.length;
        cell.className = 'muted';
        cell.textContent = 'Записи не найдены';
    }
}

async function loadRecords() {
    const version = ++loadVersion;
    const dictionary = currentDictionary;
    selectedRecord = null;
    updateButtons();

    try {
        const params = {
            search: element('search').value.trim(),
            isActive: element('inactive').checked ? 'all' : 'true',
            page: currentPage,
            size: pageSize,
            sort: sortField, direction: sortDirection
        };

        if (dictionary === 'operation-norms' && element('aircraft-type').value) {
            params.aircraftTypeId = element('aircraft-type').value;
        }

        if (dictionary === 'stands' && element('airport').value) {
            params.airportId = element('airport').value;
        }
        const data = await api.get('/ref/' + dictionary, params);
        let norms = [];

        if (version !== loadVersion) return;

        if (dictionary === 'operation-norms') {
            norms = await loadAll(dictionary, {
                isActive: 'all', aircraftTypeId: params.aircraftTypeId
            });
        }
        if (version !== loadVersion) return;

        if (currentPage > 1 && data.items.length === 0) {
            currentPage = Math.max(1, Math.ceil(data.total / pageSize));
            return loadRecords();
        }
        rows = data.items;
        normRows = norms;

        renderTable();

        element('state').textContent = '';

        renderPager(data.total);
    } catch (error) {
        if (version !== loadVersion) return;

        rows = [];
        renderTable();
        element('state').textContent = 'Не удалось загрузить записи';
        element('pager').replaceChildren();
        showError(element('page'), error);
    }
}

function switchDictionary(name) {
    currentDictionary = name;
    currentPage = 1;
    element('search').value = '';
    sortField = '';
    sortDirection = 'asc';
    element('airport-filter').classList.toggle('hidden', name !== 'stands');
    element('title').textContent = dictionaries[name].title;
    element('type-filter').classList.toggle('hidden', name !== 'operation-norms');

    for (const item of element('dicts').children) {
        const selected = item.dataset.dictionary === name;
        item.classList.toggle('selected', selected);
        item.querySelector('button').setAttribute('aria-current', String(selected));
    }
    loadRecords();
}

async function fillPredecessors(existing = []) {
    const select = element('form').elements.namedItem('prevNormIds');

    if (!select) return;

    const version = ++predecessorsVersion;
    const typeId = element('form').elements.namedItem('aircraftTypeId').value;

    select.disabled = true;
    element('save').disabled = true;

    let norms = [];

    if (typeId) {
        norms = await loadAll('operation-norms', { aircraftTypeId: typeId, isActive: 'all' });
    }
    if (version !== predecessorsVersion || !element('editor').open) return;

    select.replaceChildren();
    for (const norm of norms) {
        if (editingRecord && norm.id === editingRecord.id) continue;
        if (!norm.isActive && !existing.includes(norm.id)) continue;

        let title = norm.seqNo + '. ' + norm.operationName;

        if (!norm.isActive) title += ' (неактивен)';

        const option = new Option(title, norm.id);
        option.selected = existing.includes(norm.id);
        select.add(option);
    }
    select.disabled = false;
    element('save').disabled = false;
}

async function createInput(field, record, session) {
    const name = field[0];
    const type = field[2];
    let input;

    if (type === 'boolean') {
        input = document.createElement('input');
        input.type = 'checkbox';
        input.checked = record ? Boolean(record[name]) : true;
    } else if (type === 'multiple' || dictionaries[type]) {
        input = document.createElement('select');
        input.className = 'select';
        input.multiple = type === 'multiple';
        if (!input.multiple) {
            const options = await loadAll(type, { isActive: 'all' });
            if (session !== editorVersion || !element('editor').open) return null;
            input.add(new Option('Выберите…', ''));
            for (const option of options) {
                if (!option.isActive && (!record || option.id !== record[name])) continue;
                let title = recordLabel(option);
                if (!option.isActive) title += ' (неактивна)';
                input.add(new Option(title, option.id));
            }
            if (record) input.value = String(record[name]);
            else if (name === 'aircraftTypeId') input.value = element('aircraft-type').value;
            else input.value = '';
        }
    } else {
        input = document.createElement('input');
        input.className = 'input';
        input.type = 'text';
        if (type === 'number') {
            input.type = 'number';
            input.min = 1;
            input.max = name === 'durationMin' ? 600 : 32767;
            input.step = 1;
        } else {
            input.maxLength = type;
        }
        input.value = record ? record[name] : '';
    }
    input.name = name;
    return input;
}

async function openEditor(record = null) {
    const session = ++editorVersion;
    const dictionary = currentDictionary;
    element('editor').classList.toggle(
        'norm-editor',
        dictionary === 'operation-norms'
    );
    editingRecord = record;
    const action = record ? 'Изменить' : 'Добавить';
    element('editor-title').textContent = action + ': ' + dictionaries[dictionary].title;
    element('form-error').replaceChildren();
    element('fields').replaceChildren();
    element('save').disabled = true;
    element('editor').showModal();

    try {
        const fields = dictionaries[dictionary].fields.slice();

        if (record) fields.push(['isActive', 'Активна', 'boolean']);

        for (const field of fields) {
            const input = await createInput(field, record, session);
            if (!input || session !== editorVersion || !element('editor').open) return;
            const wrapper = document.createElement('label');
            wrapper.className = 'field';
            const title = document.createElement('span');
            title.textContent = field[1];
            wrapper.append(title, input);

            if (field[2] === 'multiple') {
                const hint = document.createElement('span');
                hint.className = 'muted small';
                hint.textContent = 'Без выбора - после постановки ВС. Ctrl / Cmd - выбрать несколько.';
                wrapper.append(hint);
            }

            element('fields').append(wrapper);
        }
        if (dictionary === 'operation-norms') {
            await fillPredecessors(record ? record.prevNormIds : []);

            if (session !== editorVersion || !element('editor').open) return;

            element('form').elements.namedItem('aircraftTypeId')
                .addEventListener('change', async function () {
                    try { await fillPredecessors(); }
                    catch (error) { showError(element('form-error'), error); }
                });
        }
        element('save').disabled = false;
        element('fields').querySelector('input, select').focus();
    } catch (error) {
        if (session === editorVersion && element('editor').open) {
            showError(element('form-error'), error);
        }
    }
}

function readForm() {
    const body = {};
    for (const input of element('fields').querySelectorAll('input, select')) {
        let value = input.value;
        if (input.multiple) {
            value = [];
            for (const option of input.selectedOptions) value.push(Number(option.value));
        } else if (input.type === 'checkbox') {
            value = input.checked;
        } else if (input.type === 'number' || input.tagName === 'SELECT') {
            value = input.value ? Number(input.value) : null;
        }
        body[input.name] = value;
    }
    if (editingRecord) {
        for (const name of Object.keys(body)) {
            if (JSON.stringify(body[name]) === JSON.stringify(editingRecord[name])) {
                delete body[name];
            }
        }
    }
    return body;
}

async function saveRecord(event) {
    event.preventDefault();
    if (saving || element('save').disabled) return;
    clearFieldErrors(element('form'));
    element('form-error').replaceChildren();
    const body = readForm();
    if (Object.keys(body).length === 0) {
        element('editor').close();
        return;
    }

    saving = true;
    element('save').disabled = true;
    element('cancel').disabled = true;
    try {
        const path = '/ref/' + currentDictionary;
        if (editingRecord) await api.patch(path + '/' + editingRecord.id, body);
        else await api.post(path, body);
        element('editor').close();
        await loadRecords();
    } catch (error) {
        if (!showFieldErrors(element('form'), error)) showError(element('form-error'), error);
    } finally {
        saving = false;
        element('save').disabled = false;
        element('cancel').disabled = false;
    }
}

async function deactivateRecord() {
    if (!selectedRecord) return;
    const record = selectedRecord;
    const dictionary = currentDictionary;
    const confirmed = await confirmDialog({
        title: 'Деактивировать запись?',
        text: record.operationName || recordLabel(record),
        ok: 'Деактивировать'
    });
    if (!confirmed) return;
    try {
        await api.patch('/ref/' + dictionary + '/' + record.id, { isActive: false });
        await loadRecords();
    } catch (error) {
        showError(element('page'), error);
    }
}

function resetPage() {
    currentPage = 1;
    loadRecords();
}

function bindEvents() {
    element('form').addEventListener('submit', saveRecord);
    element('add').addEventListener('click', function () { openEditor(); });
    element('edit').addEventListener('click', function () {
        if (selectedRecord) openEditor(selectedRecord);
    });
    element('deactivate').addEventListener('click', deactivateRecord);
    element('cancel').addEventListener('click', function () { if (!saving) element('editor').close(); });
    element('editor').addEventListener('cancel', function (event) {
        if (saving) event.preventDefault();
    });
    element('editor').addEventListener('close', function () {
        predecessorsVersion++;
        editorVersion++;
    });
    element('dicts').addEventListener('click', function (event) {
        const item = event.target.closest('li[data-dictionary]');
        if (item) switchDictionary(item.dataset.dictionary);
    });
    element('table').addEventListener('click', function (event) {
        const row = event.target.closest('tr[data-id]');
        if (row) selectRecord(Number(row.dataset.id));
    });
    element('table').addEventListener('keydown', function (event) {
        const row = event.target.closest('tr[data-id]');
        if (row && (event.key === 'Enter' || event.key === ' ')) {
            event.preventDefault();
            selectRecord(Number(row.dataset.id));
        }
    });
    element('search').addEventListener('input', function () {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(resetPage, 250);
    });
    element('inactive').addEventListener('change', resetPage);
    element('aircraft-type').addEventListener('change', resetPage);
    element('airport').addEventListener('change', resetPage);
    element('page-size').addEventListener('change', function () {
        pageSize = Number(element('page-size').value);
        resetPage();
    });
    element('close-editor').addEventListener('click', function () {
        if (!saving) element('editor').close();
    });
}

async function initialize() {
    try {
        currentUser = await startPage({ active: 'ref' });
        if (!can(currentUser, 'ref.read')) throw new Error('Нет права на просмотр справочников');

        for (const name of Object.keys(dictionaries)) {
            const item = document.createElement('li');
            item.dataset.dictionary = name;
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = dictionaries[name].title;
            item.append(button);
            element('dicts').append(item);
        }
        const airports = await loadAll('airports');
        element('airport').add(new Option('Все аэропорты', ''));
        for (const airport of airports) {
            element('airport').add(new Option(recordLabel(airport), airport.id));
        }
        const types = await loadAll('aircraft-types');
        const select = element('aircraft-type');
        select.add(new Option('Все типы ВС', ''));
        let defaultType = '';
        for (const type of types) {
            select.add(new Option(type.code, type.id));
            if (!defaultType || type.code === 'A320') defaultType = String(type.id);
        }
        select.value = defaultType;
        bindEvents();
        switchDictionary(currentDictionary);
    } catch (error) {
        showError(element('page'), error);
    }
}

initialize();
